package com.kiblatku.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private lateinit var webView: WebView
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var prefs: SharedPreferences

    private var accelerometer: Sensor? = null
    private var uncalibratedMag: Sensor? = null
    private var calibratedMag: Sensor? = null
    private var gyroscope: Sensor? = null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasMagnetic = false

    // Kalibrasi Hard-Iron Permanen (disimpan di SharedPreferences)
    private var offsetX = 0f
    private var offsetY = 0f
    private var offsetZ = 0f
    private var baselineUt = 42f
    private var isCalibratedLocked = false

    private var minX = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var minZ = Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE
    private var calSampleCount = 0

    // Complementary Filter (Giroskop + Magnetometer)
    private var fusedAzimuth = -1f
    private var lastGyroTimestampNs = 0L
    private var magneticAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var magneticFieldUt = 0f

    private var cachedDeclination = 0f
    private var lastLocation: Location? = null
    private var lastUiPushMillis = 0L
    private var pageLoaded = false
    private val LOCATION_PERMISSION_CODE = 1001

    inner class AndroidBridge {
        @JavascriptInterface
        fun vibrateAligned() {
            triggerVibration(longArrayOf(0, 80, 60, 130))
        }

        @JavascriptInterface
        fun resetCalibration() {
            resetSavedCalibration()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("KiblatKuCompassPrefs", Context.MODE_PRIVATE)
        loadSavedCalibration()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        uncalibratedMag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
        calibratedMag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            allowFileAccess = true
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoaded = true
                lastLocation?.let { sendLocationToWeb(it) }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }

        checkAndRequestLocationPermission()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun loadSavedCalibration() {
        isCalibratedLocked = prefs.getBoolean("cal_locked", false)
        offsetX = prefs.getFloat("offset_x", 0f)
        offsetY = prefs.getFloat("offset_y", 0f)
        offsetZ = prefs.getFloat("offset_z", 0f)
        baselineUt = prefs.getFloat("baseline_ut", 42f)
    }

    private fun saveCalibration(ox: Float, oy: Float, oz: Float, baseUt: Float) {
        offsetX = ox
        offsetY = oy
        offsetZ = oz
        baselineUt = baseUt
        isCalibratedLocked = true
        prefs.edit()
            .putBoolean("cal_locked", true)
            .putFloat("offset_x", ox)
            .putFloat("offset_y", oy)
            .putFloat("offset_z", oz)
            .putFloat("baseline_ut", baseUt)
            .apply()
    }

    private fun resetSavedCalibration() {
        isCalibratedLocked = false
        offsetX = 0f
        offsetY = 0f
        offsetZ = 0f
        baselineUt = 42f
        minX = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
        minZ = Float.MAX_VALUE
        maxZ = -Float.MAX_VALUE
        calSampleCount = 0
        fusedAzimuth = -1f
        prefs.edit().clear().apply()
        triggerVibration(longArrayOf(0, 50))
    }

    private fun triggerVibration(pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val audioAttrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), audioAttrs)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        lastGyroTimestampNs = 0L
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (uncalibratedMag != null) {
            sensorManager.registerListener(this, uncalibratedMag, SensorManager.SENSOR_DELAY_GAME)
        } else {
            calibratedMag?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        startNativeLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNativeLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestInitial = when {
                lastGps != null && lastNet != null -> if (lastGps.time >= lastNet.time) lastGps else lastNet
                lastGps != null -> lastGps
                else -> lastNet
            }
            bestInitial?.let { onLocationChanged(it) }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 2f, this)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this)
            }
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        val geoField = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis()
        )
        cachedDeclination = geoField.declination
        if (pageLoaded) {
            sendLocationToWeb(location)
        }
    }

    private fun sendLocationToWeb(loc: Location) {
        val lat = loc.latitude
        val lon = loc.longitude
        val acc = Math.round(loc.accuracy)
        val src = if (loc.provider == LocationManager.GPS_PROVIDER) "GPS Satelit" else "GPS Jaringan"
        webView.post {
            webView.evaluateJavascript(
                "if(window.updateNativeLocation){window.updateNativeLocation($lat, $lon, $acc, '$src');}",
                null
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !pageLoaded) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val alpha = 0.2f
                if (!hasGravity) {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                    hasGravity = true
                } else {
                    gravity[0] += alpha * (event.values[0] - gravity[0])
                    gravity[1] += alpha * (event.values[1] - gravity[1])
                    gravity[2] += alpha * (event.values[2] - gravity[2])
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (lastGyroTimestampNs != 0L && hasGravity && fusedAzimuth >= 0f) {
                    val dt = (event.timestamp - lastGyroTimestampNs) * 1e-9f
                    if (dt in 0.0001f..0.2f) {
                        val gNorm = sqrt(
                            gravity[0] * gravity[0] +
                            gravity[1] * gravity[1] +
                            gravity[2] * gravity[2]
                        )
                        if (gNorm > 1f) {
                            val gx = gravity[0] / gNorm
                            val gy = gravity[1] / gNorm
                            val gz = gravity[2] / gNorm
                            val omegaVertical = -(event.values[0] * gx + event.values[1] * gy + event.values[2] * gz)
                            val deltaDeg = Math.toDegrees((omegaVertical * dt).toDouble()).toFloat()
                            fusedAzimuth = (fusedAzimuth + deltaDeg + 360f) % 360f
                        }
                    }
                }
                lastGyroTimestampNs = event.timestamp
            }

            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                val rawX = event.values[0]
                val rawY = event.values[1]
                val rawZ = event.values[2]

                if (!isCalibratedLocked) {
                    if (offsetX == 0f && offsetY == 0f && event.values.size >= 6) {
                        offsetX = event.values[3]
                        offsetY = event.values[4]
                        offsetZ = event.values[5]
                    }
                    updateCalibrationBounds(rawX, rawY, rawZ)
                }

                val calX = rawX - offsetX
                val calY = rawY - offsetY
                val calZ = rawZ - offsetZ
                processCalibratedMagnetic(calX, calY, calZ)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticAccuracy = event.accuracy
                processCalibratedMagnetic(event.values[0], event.values[1], event.values[2])
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastUiPushMillis >= 18 && fusedAzimuth >= 0f) {
            lastUiPushMillis = now
            val headingOut = fusedAzimuth
            val utRounded = Math.round(magneticFieldUt)
            val accLevel = if (isCalibratedLocked) 3 else magneticAccuracy
            val calLockedFlag = if (isCalibratedLocked) 1 else 0

            webView.post {
                webView.evaluateJavascript(
                    "if(window.updateNativeCompass){window.updateNativeCompass($headingOut, $accLevel, $utRounded, $calLockedFlag);}",
                    null
                )
            }
        }
    }

    private fun updateCalibrationBounds(rawX: Float, rawY: Float, rawZ: Float) {
        val rawMag = sqrt(rawX * rawX + rawY * rawY + rawZ * rawZ)
        if (rawMag !in 12f..120f) return

        minX = min(minX, rawX)
        maxX = max(maxX, rawX)
        minY = min(minY, rawY)
        maxY = max(maxY, rawY)
        minZ = min(minZ, rawZ)
        maxZ = max(maxZ, rawZ)
        calSampleCount++

        val spanX = maxX - minX
        val spanY = maxY - minY
        if (calSampleCount >= 35 && spanX >= 36f && spanY >= 36f) {
            val newOx = (maxX + minX) * 0.5f
            val newOy = (maxY + minY) * 0.5f
            val newOz = if ((maxZ - minZ) >= 55f) (maxZ + minZ) * 0.5f else offsetZ
            val estBaseUt = sqrt((spanX * 0.5f) * (spanX * 0.5f) + (spanY * 0.5f) * (spanY * 0.5f)).coerceIn(22f, 65f)
            saveCalibration(newOx, newOy, newOz, estBaseUt)
            triggerVibration(longArrayOf(0, 60))
        }
    }

    private fun processCalibratedMagnetic(bx: Float, by: Float, bz: Float) {
        magneticFieldUt = sqrt(bx * bx + by * by + bz * bz)

        val alpha = 0.22f
        if (!hasMagnetic) {
            geomagnetic[0] = bx
            geomagnetic[1] = by
            geomagnetic[2] = bz
            hasMagnetic = true
        } else {
            geomagnetic[0] += alpha * (bx - geomagnetic[0])
            geomagnetic[1] += alpha * (by - geomagnetic[1])
            geomagnetic[2] += alpha * (bz - geomagnetic[2])
        }

        if (!hasGravity) return

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            val targetMatrix = if (abs(gravity[2]) < 4.5f) {
                val remapped = FloatArray(9)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    remapped
                )
                remapped
            } else {
                rotationMatrix
            }

            SensorManager.getOrientation(targetMatrix, orientation)
            var magAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat() + cachedDeclination
            magAzimuth = (magAzimuth + 360f) % 360f

            if (fusedAzimuth < 0f) {
                fusedAzimuth = magAzimuth
            } else {
                // Jika terdeteksi lonjakan medan magnet (logam/speaker), tahan arah pakai giroskop saja
                val hasSpike = isCalibratedLocked && abs(magneticFieldUt - baselineUt) > 18f
                val magWeight = if (hasSpike) 0f else 0.06f
                if (magWeight > 0f) {
                    val diff = ((magAzimuth - fusedAzimuth + 540f) % 360f) - 180f
                    fusedAzimuth = (fusedAzimuth + magWeight * diff + 360f) % 360f
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magneticAccuracy = accuracy
        }
    }

    private fun checkAndRequestLocationPermission() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )
        } else {
            startNativeLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startNativeLocationUpdates()
            webView.reload()
        }
    }
}
