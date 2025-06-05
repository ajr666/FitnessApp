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
 * 用于扫描、连接并监听 BLE 心率设备的类。
 * 调用方需要实现 [HeartRateCallback]，以接收实时心率值。
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

        // Client Characteristic Configuration Descriptor（订阅通知所需）
        private val CCC_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TAG = "HeartRateMonitor"
    }

    interface HeartRateCallback {
        /**
         * 当心率值更新时会回调本方法，hrValue 单位为 bpm
         */
        fun onHeartRateChanged(hrValue: Int)
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private var bluetoothGatt: BluetoothGatt? = null

    private val handler = Handler(Looper.getMainLooper())

    // 超时后自动停止扫描（避免无限扫描）
    private val SCAN_PERIOD: Long = 10000 // 10 秒

    init {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
    }

    /**
     * 开始扫描附近支持 Heart Rate 服务的 BLE 设备。
     * 扫描到第一个后会自动停止扫描并发起连接。
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

        // 构造过滤器，仅扫描包含 Heart Rate Service UUID 的设备
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

                // 直接连接第一个扫描到的心率设备
                connectToDevice(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(TAG, "BLE scan failed with code $errorCode")
            }
        }

        // 在 SCAN_PERIOD 后自动停止扫描
        handler.postDelayed({ stopScan() }, SCAN_PERIOD)

        bleScanner!!.startScan(listOf(filter), settings, scanCallback)
        Log.i(TAG, "Started BLE scan for Heart Rate devices.")
    }

    /**
     * 停止 BLE 扫描。
     */
    fun stopScan() {
        scanCallback?.let { callback ->
            // 检查 Android 12+ (API31+) 所需的 BLUETOOTH_SCAN 运行时权限
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
     * 连接到指定的 BLE 设备，并开始 GATT 发现服务和特征。
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
                    // 找到 Heart Rate Service
                    val hrService = gatt.getService(HR_SERVICE_UUID)
                    if (hrService != null) {
                        val hrChar = hrService.getCharacteristic(HR_MEASUREMENT_CHAR_UUID)
                        if (hrChar != null) {
                            // 订阅通知
                            val success = gatt.setCharacteristicNotification(hrChar, true)
                            if (success) {
                                // 写入 CCC Descriptor 来开启通知
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
     * 解析 Heart Rate Measurement 特征的 value 字节，得到心率值（bpm）。
     * 通常第 0 位是 flags，第 1 位是心率（uint8），如果 flag 最高位为 1，则用 uint16。
     */
    private fun parseHeartRate(data: ByteArray) {
        if (data.isEmpty()) return

        // flags = data[0] & 0xFF
        val flags = data[0].toInt() and 0xFF
        val hrValue: Int = if (flags and 0x01 == 0) {
            // 心率存储在第 1 个字节，uint8
            data[1].toInt() and 0xFF
        } else {
            // 心率存储在第 1,2 字节，uint16（低字节在前）
            ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        }

        // 切换到主线程回调更新 UI
        handler.post {
            callback.onHeartRateChanged(hrValue)
        }
    }

    /**
     * 主动断开 GATT 连接并释放资源。
     */
    fun disconnect() {
        // 检查是否拥有 BLUETOOTH_CONNECT 权限
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

    /** 断开并释放当前 GATT 连接，置空引用 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun cleanupGatt() {
        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()  // 这里需要 BLUETOOTH_CONNECT 才能安全调用
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error while closing GATT", e)
            }
        }
        bluetoothGatt = null
    }
}
