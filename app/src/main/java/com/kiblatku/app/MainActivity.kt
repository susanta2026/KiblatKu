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
    private var calibratedMag: Sensor? = null
    private var rotationVector: Sensor? = null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasMagnetic = false

    // Kalibrasi Bola 3D (X, Y, Z) untuk koreksi residu hard-iron
    private var biasX = 0f
    private var biasY = 0f
    private var biasZ = 0f
    private var is3dCalibrated = false

    private var minX = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var minZ = Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE
    private var validSampleCount = 0

    // Low-pass sudut sirkular agar jarum stabil & tidak melompat
    private var smoothAzimuth = -1f
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

        prefs = getSharedPreferences("KiblatKuCompass3DPrefs", Context.MODE_PRIVATE)
        loadSavedCalibration()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        calibratedMag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

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
        is3dCalibrated = prefs.getBoolean("cal3d_locked", false)
        biasX = prefs.getFloat("bias_x", 0f)
        biasY = prefs.getFloat("bias_y", 0f)
        biasZ = prefs.getFloat("bias_z", 0f)
    }

    private fun saveCalibration(bx: Float, by: Float, bz: Float) {
        biasX = bx
        biasY = by
        biasZ = bz
        is3dCalibrated = true
        prefs.edit()
            .putBoolean("cal3d_locked", true)
            .putFloat("bias_x", bx)
            .putFloat("bias_y", by)
            .putFloat("bias_z", bz)
            .apply()
    }

    private fun resetSavedCalibration() {
        is3dCalibrated = false
        biasX = 0f
        biasY = 0f
        biasZ = 0f
        minX = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
        minZ = Float.MAX_VALUE
        maxZ = -Float.MAX_VALUE
        validSampleCount = 0
        smoothAzimuth = -1f
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
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        calibratedMag?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotationVector?.let {
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
                val alpha = 0.18f
                if (!hasGravity) {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                    hasGravity = true
                } else {
                    gravity[0] += alpha * (event.values[0] - gravity[0])
                    gravity[1] += alpha * (event.values[1] - gravity[1])
                    gravity[2] += alpha * (event.values[2] - gravity[2])
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticAccuracy = event.accuracy
                val mx = event.values[0]
                val my = event.values[1]
                val mz = event.values[2]

                if (!is3dCalibrated) {
                    collect3dCalibrationSamples(mx, my, mz)
                }

                val cx = mx - biasX
                val cy = my - biasY
                val cz = mz - biasZ
                magneticFieldUt = sqrt(cx * cx + cy * cy + cz * cz)

                val alpha = 0.2f
                if (!hasMagnetic) {
                    geomagnetic[0] = cx
                    geomagnetic[1] = cy
                    geomagnetic[2] = cz
                    hasMagnetic = true
                } else {
                    geomagnetic[0] += alpha * (cx - geomagnetic[0])
                    geomagnetic[1] += alpha * (cy - geomagnetic[1])
                    geomagnetic[2] += alpha * (cz - geomagnetic[2])
                }

                // Gunakan matriks rotasi 3D jika kalibrasi 3D sudah terkunci atau RotationVector tidak tersedia
                if (is3dCalibrated || rotationVector == null) {
                    computeAzimuthFromGravityAndMag()
                }
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                if (!is3dCalibrated) {
                    val rotMat = FloatArray(9)
                    val orient = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                    val targetMat = if (hasGravity && abs(gravity[2]) < 4.5f) {
                        val remapped = FloatArray(9)
                        SensorManager.remapCoordinateSystem(
                            rotMat,
                            SensorManager.AXIS_X,
                            SensorManager.AXIS_Z,
                            remapped
                        )
                        remapped
                    } else {
                        rotMat
                    }
                    SensorManager.getOrientation(targetMat, orient)
                    var rawAz = Math.toDegrees(orient[0].toDouble()).toFloat() + cachedDeclination
                    rawAz = (rawAz + 360f) % 360f
                    applyCircularSmoothing(rawAz)
                }
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastUiPushMillis >= 20 && smoothAzimuth >= 0f) {
            lastUiPushMillis = now
            val headingOut = smoothAzimuth
            val utRounded = Math.round(magneticFieldUt)
            val accLevel = if (is3dCalibrated) 3 else magneticAccuracy
            val calLockedFlag = if (is3dCalibrated) 1 else 0

            webView.post {
                webView.evaluateJavascript(
                    "if(window.updateNativeCompass){window.updateNativeCompass($headingOut, $accLevel, $utRounded, $calLockedFlag);}",
                    null
                )
            }
        }
    }

    private fun collect3dCalibrationSamples(mx: Float, my: Float, mz: Float) {
        val rawUt = sqrt(mx * mx + my * my + mz * mz)
        // Hanya rekam sampel medan magnet bumi normal (22 - 75 uT)
        if (rawUt !in 22f..75f) return

        minX = min(minX, mx)
        maxX = max(maxX, mx)
        minY = min(minY, my)
        maxY = max(maxY, my)
        minZ = min(minZ, mz)
        maxZ = max(maxZ, mz)
        validSampleCount++

        val spanX = maxX - minX
        val spanY = maxY - minY
        val spanZ = maxZ - minZ

        // Wajib terkalibrasi penuh di ketiga sumbu (X, Y, dan Z) lewat gerakan angka 8
        if (validSampleCount >= 60 && spanX >= 45f && spanY >= 45f && spanZ >= 45f) {
            val bx = (maxX + minX) * 0.5f
            val by = (maxY + minY) * 0.5f
            val bz = (maxZ + minZ) * 0.5f
            saveCalibration(bx, by, bz)
            triggerVibration(longArrayOf(0, 65))
        }
    }

    private fun computeAzimuthFromGravityAndMag() {
        if (!hasGravity || !hasMagnetic) return
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
            var rawAz = Math.toDegrees(orientation[0].toDouble()).toFloat() + cachedDeclination
            rawAz = (rawAz + 360f) % 360f
            applyCircularSmoothing(rawAz)
        }
    }

    private fun applyCircularSmoothing(newAzimuth: Float) {
        if (smoothAzimuth < 0f) {
            smoothAzimuth = newAzimuth
        } else {
            val diff = ((newAzimuth - smoothAzimuth + 540f) % 360f) - 180f
            smoothAzimuth = (smoothAzimuth + 0.18f * diff + 360f) % 360f
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
