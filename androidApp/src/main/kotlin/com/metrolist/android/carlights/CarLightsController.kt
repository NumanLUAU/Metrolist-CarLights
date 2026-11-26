package com.metrolist.android.carlights

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

// >> types
data class CarLightDevice(
    val address: String,
    val name: String,
    val rssi: Int,
)

// >> types
enum class CarLightsLink {
    // feature disabled, or no saved device to look for
    IDLE,

    // scanning in the background for the saved device
    WAITING,
    CONNECTING,
    CONNECTED,
}

// ble session with one carlight controller: user discovery, connect/disconnect, background auto-connect
// to a saved address and a serialized write queue for color frames, all state changes run on the main thread
// @params: context Context -- any context, the application context is kept.
// @params: onConnected (String, String) -> Unit -- called with address and name once the lights are usable.
@SuppressLint("MissingPermission")
class CarLightsController(
    context: Context,
    private val onConnected: (address: String, name: String) -> Unit,
) {
    // >> variables
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _link = MutableStateFlow(CarLightsLink.IDLE)
    val link: StateFlow<CarLightsLink> = _link.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _discovered = MutableStateFlow<List<CarLightDevice>>(emptyList())
    val discovered: StateFlow<List<CarLightDevice>> = _discovered.asStateFlow()

    private val _bluetoothOn = MutableStateFlow(false)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    private var enabled = false
    private var autoConnect = false
    private var savedAddress = ""
    private var savedName = ""

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var family = CarLightProtocol.Family.ZONED
    private var connectedAddress: String? = null

    private var userScan: ScanCallback? = null
    private var autoScan: ScanCallback? = null

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    // zone mask to 0xRRGGBB wanted on the lights, replayed on every connect
    private var desired: Map<Int, Int> = emptyMap()
    private var lastSentFrames: List<List<Byte>> = emptyList()

    private val adapter: BluetoothAdapter?
        get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val bluetoothStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        _bluetoothOn.value = true
                        reevaluate()
                    }

                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        _bluetoothOn.value = false
                        teardown(keepScanState = false)
                        _link.value = CarLightsLink.IDLE
                    }
                }
            }
        }

    // >> main
    init {
        _bluetoothOn.value = adapter?.isEnabled == true
        ContextCompat.registerReceiver(
            appContext,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // >> functions
    // checks the runtime permissions needed to scan and connect
    fun hasPermissions(): Boolean = requiredPermissions().all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }

    // applies the saved configuration, safe to call repeatedly
    // @params: enabled Boolean -- master toggle.
    // @params: autoConnect Boolean -- scan for the saved device in the background.
    // @params: savedAddress String -- mac address of the saved lights, empty if none.
    // @params: savedName String -- advertised name of the saved lights.
    fun configure(
        enabled: Boolean,
        autoConnect: Boolean,
        savedAddress: String,
        savedName: String,
    ) {
        main.post {
            val addressChanged = this.savedAddress != savedAddress
            this.enabled = enabled
            this.autoConnect = autoConnect
            this.savedAddress = savedAddress
            this.savedName = savedName
            if (!enabled) {
                stopDiscoveryInternal()
                teardown(keepScanState = false)
                _link.value = CarLightsLink.IDLE
                return@post
            }
            if (addressChanged && connectedAddress != null && connectedAddress != savedAddress) {
                teardown(keepScanState = false)
            }
            reevaluate()
        }
    }

    // sets the zone colors, they are resent automatically after a reconnect
    // @params: zoneColors Map<Int, Int> -- zone mask to 0xRRGGBB color.
    fun setZoneColors(zoneColors: Map<Int, Int>) {
        main.post {
            desired = zoneColors
            if (_link.value == CarLightsLink.CONNECTED) sendDesired(force = false)
        }
    }

    // forces the current colors to be written again
    fun resendColors() {
        main.post { if (_link.value == CarLightsLink.CONNECTED) sendDesired(force = true) }
    }

    // rechecks permissions and bluetooth state, for example after the user granted access
    fun refresh() {
        main.post {
            _bluetoothOn.value = adapter?.isEnabled == true
            reevaluate()
        }
    }

    // starts a timed scan for supported lights so the user can pick one
    fun startDiscovery() {
        main.post {
            if (!hasPermissions() || adapter?.isEnabled != true) return@post
            val scanner = adapter?.bluetoothLeScanner ?: return@post
            stopDiscoveryInternal()
            _discovered.value = emptyList()
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        main.post { handleDiscovery(result) }
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        main.post { results.forEach(::handleDiscovery) }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Timber.tag(TAG).w("Discovery scan failed: %d", errorCode)
                        main.post { stopDiscoveryInternal() }
                    }
                }
            userScan = callback
            _discovering.value = true
            runCatching {
                scanner.startScan(
                    emptyList(),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback,
                )
            }.onFailure {
                Timber.tag(TAG).w(it, "Unable to start discovery")
                stopDiscoveryInternal()
            }
            main.postDelayed(discoveryTimeout, DISCOVERY_MS)
        }
    }

    // stops the user scan early
    fun stopDiscovery() {
        main.post { stopDiscoveryInternal() }
    }

    // connects to a light the user picked from the scan results
    // @params: device CarLightDevice -- entry from the discovered list.
    fun connect(device: CarLightDevice) {
        main.post {
            if (!hasPermissions()) return@post
            val remote = runCatching { adapter?.getRemoteDevice(device.address) }.getOrNull() ?: return@post
            stopDiscoveryInternal()
            connectInternal(remote, device.name)
        }
    }

    // drops the current connection without forgetting the saved device
    fun disconnect() {
        main.post {
            teardown(keepScanState = false)
            _link.value = CarLightsLink.IDLE
        }
    }

    // stops everything and unregisters the bluetooth state receiver
    fun release() {
        main.post {
            stopDiscoveryInternal()
            teardown(keepScanState = false)
            runCatching { appContext.unregisterReceiver(bluetoothStateReceiver) }
        }
    }

    private val discoveryTimeout = Runnable { stopDiscoveryInternal() }
    private val connectTimeout =
        Runnable {
            if (_link.value == CarLightsLink.CONNECTING) {
                Timber.tag(TAG).w("Connect timed out")
                handleLost()
            }
        }
    private val autoRetry = Runnable { reevaluate() }

    // adds a scan result to the discovered list if it is a supported light
    // @params: result ScanResult -- raw scan result.
    private fun handleDiscovery(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
        if (CarLightProtocol.familyFor(name) == null) return
        val found = CarLightDevice(result.device.address, name.orEmpty(), result.rssi)
        _discovered.value =
            (_discovered.value.filterNot { it.address == found.address } + found).sortedByDescending { it.rssi }
    }

    // stops the user scan and clears its timeout
    private fun stopDiscoveryInternal() {
        main.removeCallbacks(discoveryTimeout)
        userScan?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        userScan = null
        _discovering.value = false
    }

    // decides whether to idle, background scan for the saved device or leave the live connection alone
    private fun reevaluate() {
        main.removeCallbacks(autoRetry)
        if (!enabled || !hasPermissions() || adapter?.isEnabled != true) {
            stopAutoScan()
            if (_link.value == CarLightsLink.WAITING) _link.value = CarLightsLink.IDLE
            return
        }
        if (_link.value == CarLightsLink.CONNECTED || _link.value == CarLightsLink.CONNECTING) return
        if (autoConnect && savedAddress.isNotEmpty()) startAutoScan() else {
            stopAutoScan()
            _link.value = CarLightsLink.IDLE
        }
    }

    // starts a filtered low power scan for the saved address and connects when it shows up
    private fun startAutoScan() {
        if (autoScan != null) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val target = savedAddress
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    main.post {
                        if (autoScan !== this || !result.device.address.equals(target, ignoreCase = true)) return@post
                        val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: savedName
                        stopAutoScan()
                        connectInternal(result.device, name)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Timber.tag(TAG).w("Auto-connect scan failed: %d", errorCode)
                    main.post {
                        if (autoScan === this) {
                            autoScan = null
                            main.postDelayed(autoRetry, AUTO_RETRY_MS)
                        }
                    }
                }
            }
        autoScan = callback
        val started =
            runCatching {
                scanner.startScan(
                    listOf(ScanFilter.Builder().setDeviceAddress(target).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                    callback,
                )
            }.isSuccess
        if (started) {
            _link.value = CarLightsLink.WAITING
        } else {
            autoScan = null
            main.postDelayed(autoRetry, AUTO_RETRY_MS)
        }
    }

    // stops the background scan
    private fun stopAutoScan() {
        autoScan?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        autoScan = null
    }

    // opens the gatt connection and arms the connect timeout
    // @params: device BluetoothDevice -- the light to connect to.
    // @params: name String -- advertised name, decides the frame family.
    private fun connectInternal(
        device: BluetoothDevice,
        name: String,
    ) {
        val resolved = CarLightProtocol.familyFor(name) ?: CarLightProtocol.familyFor(savedName) ?: CarLightProtocol.Family.ZONED
        teardown(keepScanState = true)
        stopAutoScan()
        family = resolved
        connectedAddress = device.address
        _connectedName.value = name.ifEmpty { device.address }
        _link.value = CarLightsLink.CONNECTING
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        gatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            }
    }

    // connection failed or dropped, clean up and retry later if auto-connect is on
    private fun handleLost() {
        teardown(keepScanState = false)
        _link.value = CarLightsLink.IDLE
        if (enabled && autoConnect) main.postDelayed(autoRetry, AUTO_RETRY_MS)
    }

    // closes the gatt and clears the write queue
    // @params: keepScanState Boolean -- leave the background scan running.
    private fun teardown(keepScanState: Boolean) {
        main.removeCallbacks(connectTimeout)
        writeQueue.clear()
        writing = false
        lastSentFrames = emptyList()
        writeCharacteristic = null
        val g = gatt
        gatt = null
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        connectedAddress = null
        _connectedName.value = null
        if (!keepScanState) stopAutoScan()
    }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                main.post {
                    if (g !== gatt) {
                        runCatching { g.close() }
                        return@post
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        main.postDelayed({ if (g === gatt) runCatching { g.discoverServices() } }, SERVICE_DISCOVERY_DELAY_MS)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        Timber.tag(TAG).d("GATT closed status=%d state=%d", status, newState)
                        handleLost()
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                main.post {
                    if (g !== gatt) return@post
                    val characteristic =
                        g.getService(CarLightProtocol.SERVICE_UUID)?.getCharacteristic(CarLightProtocol.WRITE_UUID)
                            ?: g.services.firstNotNullOfOrNull { it.getCharacteristic(CarLightProtocol.WRITE_UUID) }
                    if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                        Timber.tag(TAG).w("No FFF3 characteristic (status=%d)", status)
                        handleLost()
                        return@post
                    }
                    writeCharacteristic = characteristic
                    main.removeCallbacks(connectTimeout)
                    _link.value = CarLightsLink.CONNECTED
                    onConnected(g.device.address, _connectedName.value.orEmpty())
                    main.postDelayed({ if (g === gatt) sendDesired(force = true) }, FIRST_WRITE_DELAY_MS)
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                main.postDelayed({
                    if (g !== gatt) return@postDelayed
                    writing = false
                    pump()
                }, WRITE_GAP_MS)
            }
        }

    // queues the frames for the wanted colors
    // @params: force Boolean -- send even if the same frames were already sent.
    private fun sendDesired(force: Boolean) {
        val frames = CarLightProtocol.colorFrames(family, desired)
        if (frames.isEmpty()) return
        val signature = frames.map { it.toList() }
        if (!force && signature == lastSentFrames) return
        lastSentFrames = signature
        writeQueue.clear()
        frames.forEach(writeQueue::addLast)
        pump()
    }

    // writes the next queued frame, one write in flight at a time
    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val characteristic = writeCharacteristic ?: return
        val frame = writeQueue.removeFirstOrNull() ?: return
        writing = true
        val noResponseOnly =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val writeType =
            if (noResponseOnly) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val accepted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(characteristic, frame, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = writeType
                    characteristic.value = frame
                    g.writeCharacteristic(characteristic)
                }
            }
        if (!accepted) {
            Timber.tag(TAG).w("Write rejected; retrying")
            writing = false
            writeQueue.addFirst(frame)
            main.postDelayed({ pump() }, WRITE_RETRY_MS)
        } else if (noResponseOnly) {
            // some stacks never call onCharacteristicWrite for write without response
            main.postDelayed({
                if (writing && g === gatt) {
                    writing = false
                    pump()
                }
            }, WRITE_RETRY_MS)
        }
    }

    // >> constants
    companion object {
        private const val TAG = "CarLights"
        private const val DISCOVERY_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val AUTO_RETRY_MS = 10_000L
        private const val SERVICE_DISCOVERY_DELAY_MS = 400L
        private const val FIRST_WRITE_DELAY_MS = 150L
        private const val WRITE_GAP_MS = 60L
        private const val WRITE_RETRY_MS = 120L

        // runtime permissions needed for the current android version
        fun requiredPermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}

// >> end << //
