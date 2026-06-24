package com.yourcompany.floatscan

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var tvVendorHint: TextView
    private lateinit var btnToggleFloat: MaterialButton
    private lateinit var tvFloatStatus: TextView

    private lateinit var cameraStatus: TextView
    private lateinit var cameraBtn: MaterialButton

    private lateinit var overlayStatus: TextView
    private lateinit var overlayBtn: MaterialButton

    private lateinit var accessibilityStatus: TextView
    private lateinit var accessibilityBtn: MaterialButton

    private lateinit var batteryStatus: TextView
    private lateinit var batteryBtn: MaterialButton

    private var notificationRequested = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionState() }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissionState() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupPermissionCards()
        tvVendorHint.text = VendorHelper.getVendorHint(this)

        btnToggleFloat.setOnClickListener {
            if (!allPermissionsGranted()) {
                Toast.makeText(this, R.string.toast_all_perms_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (FloatButtonService.isRunning(this)) {
                FloatButtonService.stop(this)
            } else {
                FloatButtonService.start(this)
                // 退出到之前的应用，避免扫码后回到本应用设置页
                moveTaskToBack(true)
            }
            refreshPermissionState()
        }

        refreshPermissionState()
        requestNotificationOnce()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun bindViews() {
        tvVendorHint = findViewById(R.id.tvVendorHint)
        btnToggleFloat = findViewById(R.id.btnToggleFloat)
        tvFloatStatus = findViewById(R.id.tvFloatStatus)

        cameraStatus = findViewById(R.id.tvCameraStatus)
        cameraBtn = findViewById(R.id.btnCameraAction)

        overlayStatus = findViewById(R.id.tvOverlayStatus)
        overlayBtn = findViewById(R.id.btnOverlayAction)

        accessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        accessibilityBtn = findViewById(R.id.btnAccessibilityAction)

        batteryStatus = findViewById(R.id.tvBatteryStatus)
        batteryBtn = findViewById(R.id.btnBatteryAction)
    }

    private fun setupPermissionCards() {
        cameraBtn.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        overlayBtn.setOnClickListener {
            overlayPermissionLauncher.launch(VendorHelper.getOverlaySettingsIntent(this))
        }

        accessibilityBtn.setOnClickListener {
            startActivity(VendorHelper.getAccessibilitySettingsIntent())
        }

        batteryBtn.setOnClickListener {
            VendorHelper.openIgnoreBatteryOptimizations(this)
        }
    }

    private fun refreshPermissionState() {
        val cameraGranted = hasCameraPermission()
        updatePermRow(cameraStatus, cameraBtn, cameraGranted)

        val overlayGranted = canDrawOverlays()
        updatePermRow(overlayStatus, overlayBtn, overlayGranted)

        val accessibilityGranted = isAccessibilityServiceEnabled()
        updatePermRow(accessibilityStatus, accessibilityBtn, accessibilityGranted)

        val batteryGranted = isBatteryOptimizationIgnored()
        updatePermRow(batteryStatus, batteryBtn, batteryGranted)

        val allGranted = allPermissionsGranted()
        btnToggleFloat.isEnabled = allGranted

        val running = FloatButtonService.isRunning(this)
        btnToggleFloat.text = if (running) {
            getString(R.string.btn_stop_float)
        } else {
            getString(R.string.btn_start_float)
        }
        tvFloatStatus.text = if (running) {
            getString(R.string.float_running)
        } else {
            getString(R.string.float_stopped)
        }
        tvFloatStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (running) R.color.status_granted else R.color.text_secondary
            )
        )
    }

    private fun updatePermRow(statusView: TextView, btn: MaterialButton, granted: Boolean) {
        if (granted) {
            statusView.text = getString(R.string.status_granted)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.status_granted))
            statusView.setBackgroundResource(0)
            btn.visibility = View.GONE
        } else {
            statusView.text = getString(R.string.status_pending)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.status_pending))
            statusView.setBackgroundResource(0)
            btn.visibility = View.VISIBLE
        }
    }

    private fun requestNotificationOnce() {
        if (notificationRequested) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationRequested = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun allPermissionsGranted(): Boolean {
        return hasCameraPermission() &&
            canDrawOverlays() &&
            isAccessibilityServiceEnabled() &&
            isBatteryOptimizationIgnored()
    }
}
