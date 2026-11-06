package com.retro.speed

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var vibrator: Vibrator? = null

    private lateinit var speedText: TextView
    private lateinit var speedUnit: TextView
    private lateinit var limitText: TextView
    private lateinit var statusText: TextView
    private lateinit var speedPanel: View

    private var currentLimitKmh = 50
    private var currentSpeedKmh = 0
    private var speedCounter = 0
    private var wasOverLimit = false
    private var flashOn = false
    private var lastQueryTime = 0L
    private var lastQueryLat = 0.0
    private var lastQueryLon = 0.0
    private var limitQueryInProgress = false

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speedText = findViewById(R.id.speedText)
        speedUnit = findViewById(R.id.speedUnit)
        limitText = findViewById(R.id.limitText)
        statusText = findViewById(R.id.statusText)
        speedPanel = findViewById(R.id.speedPanel)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        currentLimitKmh = savedInstanceState?.getInt("limit", 50) ?: 50
        limitText.text = currentLimitKmh.toString()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("limit", currentLimitKmh)
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        locationManager.removeUpdates(this)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_PERMISSION
            )
            statusText.text = getString(R.string.status_no_permission)
            return
        }
        statusText.text = getString(R.string.status_searching)
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            this
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                statusText.text = getString(R.string.status_no_permission)
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        val speedKmh = (location.speed * 3.6).roundToInt()
        currentSpeedKmh = speedKmh
        if (statusText.text == getString(R.string.status_searching)) {
            statusText.text = getString(R.string.status_gps_fix)
        }

        ObjectAnimator.ofInt(speedCounter, "text", speedCounter, speedKmh.coerceAtLeast(0))
            .apply {
                duration = 400
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val v = (anim.animatedValue as Int).coerceAtLeast(0)
                    speedText.text = v.toString()
                }
                start()
            }
        speedCounter = speedKmh.coerceAtLeast(0)

        if (hasLocationPermission()) {
            maybeQuerySpeedLimit(location.latitude, location.longitude, location.accuracy, location.bearing)
        }
        updateSpeedColors()
    }

    private fun maybeQuerySpeedLimit(lat: Double, lon: Double, accuracy: Float, bearing: Float) {
        val now = System.currentTimeMillis()
        val movedFar = distanceMeters(lat, lon, lastQueryLat, lastQueryLon) > 40.0
        if (limitQueryInProgress) return
        if (now - lastQueryTime < 8_000L && !movedFar) return

        limitQueryInProgress = true
        lastQueryTime = now
        lastQueryLat = lat
        lastQueryLon = lon

        scope.launch {
            val limit = withContext(Dispatchers.IO) { fetchSpeedLimit(lat, lon, accuracy, bearing) }
            limitQueryInProgress = false
            if (limit == null) {
                statusText.text = getString(R.string.status_auto_none)
                return@launch
            }
            statusText.text = getString(R.string.status_auto_ok)
            currentLimitKmh = limit
            limitText.text = limit.toString()
            updateSpeedColors()
        }
    }

    private fun fetchSpeedLimit(lat: Double, lon: Double, accuracy: Float, bearing: Float): Int? {
        val radius = maxOf(30f, accuracy * 2f + 15f).roundToInt()
        return try {
            val query = "[out:json][timeout:10];" +
                "way(around:$radius,$lat,$lon)[\"highway\"][\"highway\"!~\"^(footway|path|cycleway|pedestrian|steps|track|corridor)$\"][\"maxspeed\"];" +
                "out body geom;"
            val url = URL(
                "https://overpass-api.de/api/interpreter?data=" +
                    java.net.URLEncoder.encode(query, "UTF-8")
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "RetroSpeed/1.0")

            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            conn.disconnect()
            parseNearestMaxspeed(body, lat, lon, bearing)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseNearestMaxspeed(json: String, lat: Double, lon: Double, bearing: Float): Int? {
        return try {
            val elements = JSONObject(json).getJSONArray("elements")
            var best: Int? = null
            var bestScore = Double.MAX_VALUE
            val hasBearing = bearing.isFinite() && bearing != 0.0f
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                if (el.optString("type") != "way") continue
                val maxspeedRaw = el.optJSONObject("tags")?.optString("maxspeed") ?: continue
                val kmh = parseMaxspeed(maxspeedRaw) ?: continue
                val geom = el.optJSONArray("geometry") ?: continue
                var minDist = Double.MAX_VALUE
                var bestSegBearing = Float.NaN
                for (j in 0 until geom.length() - 1) {
                    val a = geom.getJSONObject(j)
                    val b = geom.getJSONObject(j + 1)
                    val d = pointToSegmentMeters(
                        lat, lon,
                        a.getDouble("lat"), a.getDouble("lon"),
                        b.getDouble("lat"), b.getDouble("lon")
                    )
                    if (d < minDist) {
                        minDist = d
                        bestSegBearing = segmentBearing(
                            a.getDouble("lat"), a.getDouble("lon"),
                            b.getDouble("lat"), b.getDouble("lon")
                        )
                    }
                }
                var score = minDist
                if (hasBearing && bestSegBearing.isFinite()) {
                    val diff = bearingDiffDeg(bearing, bestSegBearing)
                    if (diff > 60.0) score *= 3.0
                }
                if (score < bestScore) {
                    bestScore = score
                    best = kmh
                }
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    private fun segmentBearing(alat: Double, alon: Double, blat: Double, blon: Double): Float {
        val dy = (blat - alat) * 110_540.0
        val dx = (blon - alon) * 111_320.0 * cos(Math.toRadians((alat + blat) / 2.0))
        if (dx == 0.0 && dy == 0.0) return Float.NaN
        return ((Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0).toFloat()
    }

    private fun bearingDiffDeg(a: Float, b: Float): Double {
        val d = kotlin.math.abs((a - b).toDouble()) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun parseMaxspeed(raw: String): Int? {
        val clean = raw.trim()
        if (clean.startsWith("RU:") || clean.contains(";")) return null
        val mphMatch = Regex("^(\\d+)\\s*mph$").find(clean)
        if (mphMatch != null) {
            return (mphMatch.groupValues[1].toInt() * 1.60934).roundToInt()
        }
        val kmhMatch = Regex("^(\\d+)$").find(clean)
        return kmhMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun pointToSegmentMeters(
        plat: Double, plon: Double,
        alat: Double, alon: Double,
        blat: Double, blon: Double
    ): Double {
        fun toXY(lat: Double, lon: Double): Pair<Double, Double> {
            val x = lon * 111_320.0 * cos(Math.toRadians(lat))
            val y = lat * 110_540.0
            return x to y
        }
        val (px, py) = toXY(plat, plon)
        val (ax, ay) = toXY(alat, alon)
        val (bx, by) = toXY(blat, blon)
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0
        else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val ex = px - cx
        val ey = py - cy
        return sqrt(ex * ex + ey * ey)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return Double.MAX_VALUE
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 637_1000.0 * 2 * asin(sqrt(a))
    }

    private fun updateSpeedColors() {
        val over = currentSpeedKmh > currentLimitKmh
        if (over) {
            speedPanel.setBackgroundResource(R.drawable.bg_speed_panel_over)
            speedText.setTextColor(Color.parseColor("#FF6E40"))
            speedUnit.setTextColor(Color.parseColor("#FFCCBC"))
            limitText.setTextColor(Color.parseColor("#FFD166"))
            if (!wasOverLimit) {
                wasOverLimit = true
                buzz()
            }
            flashOn = !flashOn
            speedPanel.alpha = if (flashOn) 1f else 0.75f
        } else {
            speedPanel.setBackgroundResource(R.drawable.bg_speed_panel)
            speedPanel.alpha = 1f
            val moving = currentSpeedKmh > 0
            if (moving) {
                speedText.setTextColor(Color.parseColor("#9CCC65"))
                speedUnit.setTextColor(Color.parseColor("#C5E1A5"))
            } else {
                speedText.setTextColor(Color.parseColor("#FFB74D"))
                speedUnit.setTextColor(Color.parseColor("#FFCC80"))
            }
            limitText.setTextColor(Color.parseColor("#FFF3C4"))
            wasOverLimit = false
        }
    }

    private fun buzz() {
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(longArrayOf(0, 200, 100, 200), -1)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        statusText.text = getString(R.string.status_gps_off)
    }

    companion object {
        private const val REQ_PERMISSION = 42
    }
}
