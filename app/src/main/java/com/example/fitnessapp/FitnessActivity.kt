package com.example.fitnessapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

class FitnessActivity : AppCompatActivity(),
    SensorEventListener,
    OnMapReadyCallback {

    private val TAG = "FitnessActivity"

    // 步数 / 加速度
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var usingHardwareCounter = false
    private var isTracking = false
    private var baseStepCount: Float = -1f
    private var currentStepCount: Float = 0f
    private var lastAccelMagnitude = 0f
    private var stepEstimate = 0

    // 计时
    private lateinit var tvStepCount: TextView
    private lateinit var tvTimer: TextView
    private var startTimeMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsedMs = System.currentTimeMillis() - startTimeMs
            val totalSeconds = elapsedMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60

            val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)
            tvTimer.text = "Elapsed Time: $timeString"

            handler.postDelayed(this, 1000)
        }
    }

    // 地图与定位
    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private val pathPoints = mutableListOf<LatLng>()
    private var currentPolyline: Polyline? = null

    // 心率监测器
    private lateinit var tvHeartRate: TextView
    private var heartRateMonitor: HeartRateMonitor? = null

    // 按钮
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // 运行时权限请求 Launcher
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val scanGranted = perms[Manifest.permission.BLUETOOTH_SCAN] == true
        val locGranted  = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val recoGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            perms[Manifest.permission.ACTIVITY_RECOGNITION] == true else true

        if (scanGranted && locGranted && recoGranted) {
            startBleAndTracking()
        } else {
            Log.w(TAG, "Permission denied: " +
                    "SCAN=$scanGranted LOC=$locGranted RECO=$recoGranted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fitness)

        // —— 绑定 UI 组件 ——
        tvStepCount = findViewById(R.id.tvStepCount)
        tvTimer = findViewById(R.id.tvTimer)
        tvHeartRate = findViewById(R.id.tvHeartRate)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // —— 传感器初始化 ——
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // —— 地图 / 定位 初始化 ——
        val mapFragment =
            supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply {
            interval = 5000           // 5 秒请求一次
            fastestInterval = 2000    // 最快 2 秒更新
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    Log.d("Loc", "lat=${loc.latitude}, lon=${loc.longitude}")
                    addLocationPoint(loc)
                    Log.d("Polyline", "size=${pathPoints.size}")
                }
            }
        }

        // —— 心率监测器 初始化 ——
        heartRateMonitor = HeartRateMonitor(this, object : HeartRateMonitor.HeartRateCallback {
            override fun onHeartRateChanged(hrValue: Int) {
                // 在主线程更新 UI
                tvHeartRate.text = "Heart Rate: $hrValue bpm"
            }
        })

        // —— 按钮点击事件 ——
        btnStart.setOnClickListener {
            // 点击“Start Tracking”时，先请求 BLE_SCAN & ACCESS_FINE_LOCATION 权限
            requestBlePermissions()
        }
        btnStop.setOnClickListener {
            // 停止所有跟踪：传感器、定位、心率
            stopTracking()
            heartRateMonitor?.disconnect()
            tvHeartRate.text = "Heart Rate: -- bpm"
        }
    }

    /**
     * 实际启动步数/地图/心率三项跟踪，前提是已经拿到 BLE_SCAN & ACCESS_FINE_LOCATION。
     */
    private fun startBleAndTracking() {
        // —— 步数/加速度注册 ——
        if (stepSensor != null) {
            usingHardwareCounter = true
            baseStepCount = -1f
            currentStepCount = 0f
            sensorManager.registerListener(
                this,
                stepSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.d("FitnessActivity", "Using hardware step counter")
        } else if (accelSensor != null) {
            usingHardwareCounter = false
            stepEstimate = 0
            lastAccelMagnitude = 0f
            sensorManager.registerListener(
                this,
                accelSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        } else {
            tvStepCount.text = "No step or accelerometer sensor available"
            // 但心率和地图仍然可以工作
        }

        // —— 地图定位更新 ——
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }

        // —— 心率扫描 ——
        heartRateMonitor?.startScan()

        // —— 启动计时 ——
        startTimeMs = System.currentTimeMillis()
        handler.post(timerRunnable)

        isTracking = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    /**
     * 点击“Start Tracking”时先调用的入口，检查并请求 BLE_SCAN 与 LOCATION 权限。
     */
    private fun requestBlePermissions() {
        val toRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toRequest.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (toRequest.isNotEmpty()) {
            // 现在一次性申请所有缺的权限
            blePermissionLauncher.launch(toRequest.toTypedArray())
        } else {
            // 已全部到位
            startBleAndTracking()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // 地图上加一个默认起点标记
        val defaultCity = LatLng(47.6062, -122.3321) // Seattle
        mMap.addMarker(
            MarkerOptions()
                .position(defaultCity)
                .title("Start Point")
        )
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultCity, 17f))

        // 打开“我的位置”图层，如果已有权限
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true
        }
    }

    /**
     * 停止传感器监听、定位更新、计时，以及断开心率连接。
     */
    private fun stopTracking() {
        isTracking = false
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        handler.removeCallbacks(timerRunnable)

        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        Log.d("FitnessActivity", "go to Sensor changed")
        if (!isTracking || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values[0]
                if (baseStepCount < 0) {
                    baseStepCount = totalSteps
                }
                currentStepCount = totalSteps - baseStepCount
                tvStepCount.text = "Steps: ${currentStepCount.toInt()}"
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                val delta = Math.abs(magnitude - lastAccelMagnitude)
                if (delta > 6) {
                    stepEstimate += 1
                    tvStepCount.text = "Steps (est): $stepEstimate"
                }
                lastAccelMagnitude = magnitude
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不用处理
    }

    /**
     * 把每次定位点加入到 pathPoints 并绘制折线
     */
    private fun addLocationPoint(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        pathPoints.add(latLng)

        if (currentPolyline == null) {
            currentPolyline = mMap.addPolyline(
                PolylineOptions()
                    .addAll(pathPoints)
                    .width(8f)
                    .color(0xFF2196F3.toInt())
            )
        } else {
            currentPolyline?.points = pathPoints
        }

        mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
    }

    override fun onPause() {
        super.onPause()
        if (isTracking) {
            stopTracking()
            heartRateMonitor?.disconnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        heartRateMonitor?.disconnect()
    }
}
