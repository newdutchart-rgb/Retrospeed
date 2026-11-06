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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var vibrator: Vibrator? = null

    private lateinit var speedText: TextView
    private lateinit var speedUnit: TextView
    private lateinit var limitText: TextView
    private lateinit var limitLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var limitRow: LinearLayout
    private lateinit var speedPanel: View

    private var currentLimitKmh = 50
    private var currentSpeedKmh = 0
    private var speedCounter = 0
    private var wasOverLimit = false
    private var flashOn = false

    private val limitButtons = mutableListOf<Pair<TextView, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speedText = findViewById(R.id.speedText)
        speedUnit = findViewById(R.id.speedUnit)
        limitText = findViewById(R.id.limitText)
        limitLabel = findViewById(R.id.limitLabel)
        statusText = findViewById(R.id.statusText)
        limitRow = findViewById(R.id.limitRow)
        speedPanel = findViewById(R.id.speedPanel)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        buildLimitButtons()
        selectLimit(50)

        if (savedInstanceState != null) {
            selectLimit(savedInstanceState.getInt("limit", 50))
        }
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

    private fun buildLimitButtons() {
        val limits = listOf(30, 50, 60, 80, 100, 120)
        limitRow.removeAllViews()
        limitButtons.clear()
        limits.forEach { limit ->
            val btn = layoutInflater.inflate(R.layout.item_limit_button, limitRow, false) as TextView
            btn.text = limit.toString()
            btn.setOnClickListener { selectLimit(limit) }
            limitRow.addView(btn)
            limitButtons.add(btn to limit)
        }
    }

    private fun selectLimit(limit: Int) {
        currentLimitKmh = limit
        limitText.text = limit.toString()
        limitButtons.forEach { (btn, value) ->
            val selected = value == limit
            btn.isSelected = selected
        }
        updateOverLimitState()
    }

    override fun onLocationChanged(location: Location) {
        val speedKmh = (location.speed * 3.6).roundToInt()
        currentSpeedKmh = speedKmh
        statusText.text = getString(R.string.status_gps_fix)

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
        updateOverLimitState()
    }

    private fun updateOverLimitState() {
        val over = currentSpeedKmh > currentLimitKmh
        if (over) {
            speedPanel.setBackgroundResource(R.drawable.bg_speed_panel_over)
            speedText.setTextColor(Color.parseColor("#FFF3C4"))
            speedUnit.setTextColor(Color.parseColor("#FFF3C4"))
            limitText.setTextColor(Color.parseColor("#FFD166"))
            limitLabel.setTextColor(Color.parseColor("#FFE0B2"))
            if (!wasOverLimit) {
                wasOverLimit = true
                buzz()
            }
            flashOn = !flashOn
            speedPanel.alpha = if (flashOn) 1f else 0.75f
        } else {
            speedPanel.setBackgroundResource(R.drawable.bg_speed_panel)
            speedPanel.alpha = 1f
            speedText.setTextColor(Color.parseColor("#FFB74D"))
            speedUnit.setTextColor(Color.parseColor("#FFCC80"))
            limitText.setTextColor(Color.parseColor("#FFF3C4"))
            limitLabel.setTextColor(Color.parseColor("#FFE0B2"))
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
