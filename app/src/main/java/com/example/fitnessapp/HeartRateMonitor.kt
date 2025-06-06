package com.example.fitnessapp

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.util.*

/**
 * Class for scanning, connecting, and listening to BLE heart rate devices.
 * The caller must implement [HeartRateCallback] to receive real-time heart rate values.
 */
class HeartRateMonitor(
    private val context: Context,
    private val callback: HeartRateCallback
) {

    companion object {
        // BLE Heart Rate Service UUID
        private val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        // Heart Rate Measurement Characteristic UUID
        private val HR_MEASUREMENT_CHAR_UUID: UUID =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        // Client Characteristic Configuration Descriptor (required for notification subscription)
        private val CCC_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TAG = "HeartRateMonitor"
    }

    interface HeartRateCallback {
        /**
         * Called when a new heart rate value is received; hrValue is in bpm
         */
        fun onHeartRateChanged(hrValue: Int)
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private var bluetoothGatt: BluetoothGatt? = null

    private val handler = Handler(Looper.getMainLooper())

    // Automatically stop scan after timeout to prevent infinite scanning
    private val SCAN_PERIOD: Long = 10000 // 10 seconds

    init {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
    }

    /**
     * Start scanning for nearby BLE devices that support Heart Rate service.
     * Automatically stops after the first match and initiates connection.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled or not available.")
            return
        }

        bleScanner = bluetoothAdapter!!.bluetoothLeScanner
        if (bleScanner == null) {
            Log.e(TAG, "Cannot get BLE scanner.")
            return
        }

        // Filter to scan only devices with Heart Rate Service UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                Log.i(TAG, "Found device: ${result.device.address}, stopping scan and connecting…")
                stopScan()

                // Connect to the first heart rate device found
                connectToDevice(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(TAG, "BLE scan failed with code $errorCode")
            }
        }

        // Stop scan automatically after SCAN_PERIOD
        handler.postDelayed({ stopScan() }, SCAN_PERIOD)

        bleScanner!!.startScan(listOf(filter), settings, scanCallback)
        Log.i(TAG, "Started BLE scan for Heart Rate devices.")
    }

    /**
     * Stop BLE scanning.
     */
    fun stopScan() {
        scanCallback?.let { callback ->
            // Check for BLUETOOTH_SCAN permission required on Android 12+
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bleScanner?.stopScan(callback)
                Log.i(TAG, "Stopped BLE scan.")
            } else {
                Log.w(TAG, "Cannot stopScan(): missing BLUETOOTH_SCAN permission.")
            }
        }
        scanCallback = null
    }

    /**
     * Connect to the specified BLE device and start GATT service and characteristic discovery.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT server. Starting service discovery…")
                    gatt.discoverServices()
                } else {
                    Log.e(TAG, "Failed to connect or disconnected: status=$status, state=$newState")
                    cleanupGatt()
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(gatt, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Locate the Heart Rate Service
                    val hrService = gatt.getService(HR_SERVICE_UUID)
                    if (hrService != null) {
                        val hrChar = hrService.getCharacteristic(HR_MEASUREMENT_CHAR_UUID)
                        if (hrChar != null) {
                            // Subscribe to notifications
                            val success = gatt.setCharacteristicNotification(hrChar, true)
                            if (success) {
                                // Write CCC descriptor to enable notifications
                                val descriptor = hrChar.getDescriptor(CCC_DESCRIPTOR_UUID)
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                                Log.i(TAG, "Subscribed to Heart Rate notifications.")
                            } else {
                                Log.e(TAG, "Failed to set characteristic notification.")
                            }
                        }
                    } else {
                        Log.e(TAG, "Heart Rate service not found.")
                    }
                } else {
                    Log.e(TAG, "onServicesDiscovered received status $status")
                    cleanupGatt()
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                super.onDescriptorWrite(gatt, descriptor, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Descriptor write successful, notifications enabled.")
                } else {
                    Log.e(TAG, "Descriptor write failed with status $status")
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                super.onCharacteristicChanged(gatt, characteristic)
                if (characteristic.uuid == HR_MEASUREMENT_CHAR_UUID) {
                    parseHeartRate(characteristic.value)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                super.onCharacteristicRead(gatt, characteristic, status)
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == HR_MEASUREMENT_CHAR_UUID) {
                    parseHeartRate(characteristic.value)
                }
            }
        })
    }

    /**
     * Parse the value of the Heart Rate Measurement characteristic to get bpm.
     * Usually, byte[0] is flags; byte[1] is HR in uint8 unless flag's MSB is 1 (then use uint16).
     */
    private fun parseHeartRate(data: ByteArray) {
        if (data.isEmpty()) return

        val flags = data[0].toInt() and 0xFF
        val hrValue: Int = if (flags and 0x01 == 0) {
            // HR stored in byte 1 (uint8)
            data[1].toInt() and 0xFF
        } else {
            // HR stored in bytes 1 and 2 (uint16, little-endian)
            ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        }

        // Post result to main thread for UI update
        handler.post {
            callback.onHeartRateChanged(hrValue)
        }
    }

    /**
     * Manually disconnect from GATT and release resources.
     */
    fun disconnect() {
        // Check BLUETOOTH_CONNECT permission
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            cleanupGatt()
        } else {
            Log.w(TAG, "Skipping disconnect(): missing BLUETOOTH_CONNECT permission")
        }
    }

    /** Disconnect and release current GATT connection */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun cleanupGatt() {
        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()  // Requires BLUETOOTH_CONNECT permission
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error while closing GATT", e)
            }
        }
        bluetoothGatt = null
    }
}
