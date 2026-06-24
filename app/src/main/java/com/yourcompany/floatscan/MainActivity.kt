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
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var tvVendorHint: TextView
    private lateinit var btnToggleFloat: MaterialButton
    private lateinit var tvFloatStatus: TextView

    private lateinit var cameraTitle: TextView
    private lateinit var cameraDesc: TextView
    private lateinit var cameraStatus: TextView
    private lateinit var cameraBtn: MaterialButton

    private lateinit var overlayTitle: TextView
    private lateinit var overlayDesc: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var overlayBtn: MaterialButton

    private lateinit var accessibilityTitle: TextView
    private lateinit var accessibilityDesc: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var accessibilityBtn: MaterialButton

    private lateinit var batteryTitle: TextView
    private lateinit var batteryDesc: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var batteryBtn: MaterialButton

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
            }
            refreshPermissionState()
        }

        refreshPermissionState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun bindViews() {
        tvVendorHint = findViewById(R.id.tvVendorHint)
        btnToggleFloat = findViewById(R.id.btnToggleFloat)
        tvFloatStatus = findViewById(R.id.tvFloatStatus)

        val cardCamera = findViewById<MaterialCardView>(R.id.cardCamera)
        cameraTitle = cardCamera.findViewById(R.id.tvPermTitle)
        cameraDesc = cardCamera.findViewById(R.id.tvPermDesc)
        cameraStatus = cardCamera.findViewById(R.id.tvPermStatus)
        cameraBtn = cardCamera.findViewById(R.id.btnPermAction)

        val cardOverlay = findViewById<MaterialCardView>(R.id.cardOverlay)
        overlayTitle = cardOverlay.findViewById(R.id.tvPermTitle)
        overlayDesc = cardOverlay.findViewById(R.id.tvPermDesc)
        overlayStatus = cardOverlay.findViewById(R.id.tvPermStatus)
        overlayBtn = cardOverlay.findViewById(R.id.btnPermAction)

        val cardAccessibility = findViewById<MaterialCardView>(R.id.cardAccessibility)
        accessibilityTitle = cardAccessibility.findViewById(R.id.tvPermTitle)
        accessibilityDesc = cardAccessibility.findViewById(R.id.tvPermDesc)
        accessibilityStatus = cardAccessibility.findViewById(R.id.tvPermStatus)
        accessibilityBtn = cardAccessibility.findViewById(R.id.btnPermAction)

        val cardBattery = findViewById<MaterialCardView>(R.id.cardBattery)
        batteryTitle = cardBattery.findViewById(R.id.tvPermTitle)
        batteryDesc = cardBattery.findViewById(R.id.tvPermDesc)
        batteryStatus = cardBattery.findViewById(R.id.tvPermStatus)
        batteryBtn = cardBattery.findViewById(R.id.btnPermAction)
    }

    private fun setupPermissionCards() {
        cameraTitle.text = getString(R.string.perm_camera)
        cameraDesc.text = getString(R.string.perm_camera_desc)
        cameraBtn.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        overlayTitle.text = getString(R.string.perm_overlay)
        overlayDesc.text = getString(R.string.perm_overlay_desc)
        overlayBtn.setOnClickListener {
            overlayPermissionLauncher.launch(VendorHelper.getOverlaySettingsIntent(this))
        }

        accessibilityTitle.text = getString(R.string.perm_accessibility)
        accessibilityDesc.text = getString(R.string.perm_accessibility_desc)
        accessibilityBtn.setOnClickListener {
            startActivity(VendorHelper.getAccessibilitySettingsIntent())
        }

        batteryTitle.text = getString(R.string.perm_battery)
        batteryDesc.text = getString(R.string.perm_battery_desc)
        batteryBtn.setOnClickListener {
            VendorHelper.openIgnoreBatteryOptimizations(this)
        }
    }

    private fun refreshPermissionState() {
        requestNotificationIfNeeded()

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
    }

    private fun updatePermRow(statusView: TextView, btn: MaterialButton, granted: Boolean) {
        if (granted) {
            statusView.text = getString(R.string.status_granted)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.status_granted))
            btn.visibility = View.GONE
        } else {
            statusView.text = getString(R.string.status_pending)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.status_pending))
            btn.visibility = View.VISIBLE
        }
    }

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
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
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
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
