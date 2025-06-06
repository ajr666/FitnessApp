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

    // Step count / Accelerometer
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var usingHardwareCounter = false
    private var isTracking = false
    private var baseStepCount: Float = -1f
    private var currentStepCount: Float = 0f
    private var lastAccelMagnitude = 0f
    private var stepEstimate = 0

    // Timer
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

    // Map and Location
    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private val pathPoints = mutableListOf<LatLng>()
    private var currentPolyline: Polyline? = null

    // Heart Rate Monitor
    private lateinit var tvHeartRate: TextView
    private var heartRateMonitor: HeartRateMonitor? = null

    // Buttons
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // Runtime permission request launcher
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

        // Bind UI components
        tvStepCount = findViewById(R.id.tvStepCount)
        tvTimer = findViewById(R.id.tvTimer)
        tvHeartRate = findViewById(R.id.tvHeartRate)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // Sensor initialization
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Map / Location initialization
        val mapFragment =
            supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply {
            interval = 5000           // Request every 5 seconds
            fastestInterval = 2000    // Update as fast as every 2 seconds
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

        // Heart rate monitor initialization
        heartRateMonitor = HeartRateMonitor(this, object : HeartRateMonitor.HeartRateCallback {
            override fun onHeartRateChanged(hrValue: Int) {
                // Update UI on main thread
                tvHeartRate.text = "Heart Rate: $hrValue bpm"
            }
        })

        // Button click events
        btnStart.setOnClickListener {
            // When "Start Tracking" is clicked, request BLE_SCAN & ACCESS_FINE_LOCATION permissions
            requestBlePermissions()
        }
        btnStop.setOnClickListener {
            // Stop all tracking: sensors, location, heart rate
            stopTracking()
            heartRateMonitor?.disconnect()
            tvHeartRate.text = "Heart Rate: -- bpm"
        }
    }

    /**
     * Actually start tracking steps, map, and heart rate.
     * Assumes BLE_SCAN & ACCESS_FINE_LOCATION permissions are already granted.
     */
    private fun startBleAndTracking() {
        // Register step/acceleration sensor
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
            // But heart rate and map can still work
        }

        // Start location updates
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

        // Start heart rate scan
        heartRateMonitor?.startScan()

        // Start timer
        startTimeMs = System.currentTimeMillis()
        handler.post(timerRunnable)

        isTracking = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    /**
     * Entry point when "Start Tracking" is clicked;
     * checks and requests BLE_SCAN and LOCATION permissions.
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
            // Now request all missing permissions at once
            blePermissionLauncher.launch(toRequest.toTypedArray())
        } else {
            // All permissions are already granted
            startBleAndTracking()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Add a default starting marker on the map
        val defaultCity = LatLng(47.6062, -122.3321) // Seattle
        mMap.addMarker(
            MarkerOptions()
                .position(defaultCity)
                .title("Start Point")
        )
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultCity, 17f))

        // Enable "My Location" layer if permission is already granted
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true
        }
    }

    /**
     * Stop sensor listening, location updates, timer,
     * and disconnect heart rate monitor.
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
        // No need to handle this
    }

    /**
     * Add each location point to pathPoints and draw polyline
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
