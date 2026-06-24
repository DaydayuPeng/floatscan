package com.yourcompany.floatscan

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * 厂商检测与专属设置页跳转工具。
 */
object VendorHelper {

    enum class Vendor {
        XIAOMI,
        HUAWEI,
        OPPO,
        VIVO,
        SAMSUNG,
        OTHER
    }

    fun getVendor(): Vendor {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> Vendor.XIAOMI
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Vendor.HUAWEI
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> Vendor.OPPO
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> Vendor.VIVO
            manufacturer.contains("samsung") -> Vendor.SAMSUNG
            else -> Vendor.OTHER
        }
    }

    fun getVendorHint(context: Context): String {
        return when (getVendor()) {
            Vendor.XIAOMI -> context.getString(R.string.vendor_hint_xiaomi)
            Vendor.HUAWEI -> context.getString(R.string.vendor_hint_huawei)
            Vendor.OPPO -> context.getString(R.string.vendor_hint_oppo)
            Vendor.VIVO -> context.getString(R.string.vendor_hint_vivo)
            Vendor.SAMSUNG -> context.getString(R.string.vendor_hint_samsung)
            Vendor.OTHER -> context.getString(R.string.vendor_hint_default)
        }
    }

    fun getOverlaySettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun getBatteryOptimizationIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") -> {
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", context.packageName)
                }
            }
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
                Intent().apply {
                    setClassName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }
            }
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                Intent("com.vivo.permissionmanager.action.BG_LAUNCH_MANAGER")
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                Intent().apply {
                    setClassName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                }
            }
            manufacturer.contains("samsung") -> {
                Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.fromParts("package", context.packageName, null)
                }
            }
            else -> null
        }
    }

    fun openBatterySettings(context: Context) {
        val intent = getBatteryOptimizationIntent(context)
        if (intent != null) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // fall through
            } catch (_: Exception) {
                // fall through
            }
        }

        try {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        } catch (_: Exception) {
            // ignore
        }
        Toast.makeText(context, R.string.toast_battery_manual, Toast.LENGTH_LONG).show()
    }

    fun openIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openBatterySettings(context)
        }
    }
}
