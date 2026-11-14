package com.antigravity.duplicleaner.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Throttle levels reflecting device thermal status and battery power state.
 */
enum class ThrottleLevel(
    val label: String,
    val maxParallelWorkers: Int,
    val chunkCooldownMs: Long
) {
    NORMAL("최고 속도", 4, 0L),
    BALANCED("전력 절약", 2, 15L),
    THERMAL_COOLING("발열 보호 감속", 1, 45L)
}

/**
 * Snapshot of device battery and thermal status at a specific point in time.
 */
data class DeviceHealthSnapshot(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val thermalStatusCode: Int,
    val throttleLevel: ThrottleLevel
)

/**
 * Monitor utility to check device thermal and battery state and determine
 * dynamic throttling levels for intensive background media processing.
 */
object ThermalBatteryMonitor {

    /**
     * Pure Kotlin decision logic to resolve throttle level.
     *
     * Rules:
     * - thermalStatusCode >= 3 (PowerManager.THERMAL_STATUS_SEVERE+): THERMAL_COOLING
     * - thermalStatusCode == 2 (THERMAL_STATUS_MODERATE) or (!isCharging && batteryPercent in 1..20): BALANCED
     * - otherwise: NORMAL
     */
    fun resolveThrottleLevel(
        batteryPercent: Int,
        isCharging: Boolean,
        thermalStatusCode: Int
    ): ThrottleLevel {
        return when {
            thermalStatusCode >= 3 -> ThrottleLevel.THERMAL_COOLING
            thermalStatusCode == 2 || (!isCharging && batteryPercent in 1..20) -> ThrottleLevel.BALANCED
            else -> ThrottleLevel.NORMAL
        }
    }

    /**
     * Safely reads battery state and thermal status from the system.
     * In case of any error or permission issue, defaults safely to (100, true, 0, NORMAL).
     */
    fun inspectDeviceHealth(context: Context): DeviceHealthSnapshot {
        return try {
            val batteryStatusIntent: Intent? = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            val level = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPercent = if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100).toInt().coerceIn(0, 100)
            } else {
                100
            }

            val status = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val thermalStatusCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.currentThermalStatus ?: 0
            } else {
                0
            }

            val throttleLevel = resolveThrottleLevel(
                batteryPercent = batteryPercent,
                isCharging = isCharging,
                thermalStatusCode = thermalStatusCode
            )

            DeviceHealthSnapshot(
                batteryPercent = batteryPercent,
                isCharging = isCharging,
                thermalStatusCode = thermalStatusCode,
                throttleLevel = throttleLevel
            )
        } catch (_: Throwable) {
            DeviceHealthSnapshot(
                batteryPercent = 100,
                isCharging = true,
                thermalStatusCode = 0,
                throttleLevel = ThrottleLevel.NORMAL
            )
        }
    }
}
