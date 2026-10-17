package com.bizzle.minicpm.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File

/** RAM, thermal and CPU facts used for the status chip and the "safe to load?" check. */
object DeviceStatus {
    data class Snapshot(val availRamBytes: Long, val totalRamBytes: Long, val lowMemory: Boolean, val thermal: Int, val cores: Int, val soc: String) {
        val thermalLabel: String get() = when (thermal) {
            PowerManager.THERMAL_STATUS_NONE -> "Cool"; PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
            PowerManager.THERMAL_STATUS_MODERATE -> "Hot"; PowerManager.THERMAL_STATUS_SEVERE -> "Very hot"
            PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "Critical"
            else -> "?"
        }
    }

    fun snapshot(ctx: Context): Snapshot {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pm = ctx.getSystemService(PowerManager::class.java)
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return Snapshot(mi.availMem, mi.totalMem, mi.lowMemory, pm.currentThermalStatus, Runtime.getRuntime().availableProcessors(), soc)
    }

    /** Tensor G5 reports SOC_MODEL "Tensor G5". Used to warn before loading the TPU-only model elsewhere. */
    fun isTensorG5(): Boolean = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.contains("G5", ignoreCase = true) && Build.SOC_MANUFACTURER.contains("Google", true)

    fun cpuFeatures(): String = runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") || it.startsWith("flags") }?.substringAfter(':')?.trim() }.getOrNull() ?: ""
}
