package ovh.battistella.ondes.sync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

object OemAutostart {

    const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

    private val XIAOMI = setOf("xiaomi", "redmi", "poco")
    private val HUAWEI = setOf("huawei", "honor")
    private val OPPO = setOf("oppo", "realme", "oneplus")
    private val VIVO = setOf("vivo", "iqoo")

    private val XIAOMI_COMPONENTS = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
    )
    private val HUAWEI_COMPONENTS = listOf(
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
    )
    private val OPPO_COMPONENTS = listOf(
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.battery" to "com.oplus.startupapp.view.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
    )
    private val VIVO_COMPONENTS = listOf(
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
    )

    val queriedPackages: List<String> =
        (XIAOMI_COMPONENTS + HUAWEI_COMPONENTS + OPPO_COMPONENTS + VIVO_COMPONENTS)
            .map { it.first }
            .distinct()

    fun isSupported(manufacturer: String = Build.MANUFACTURER): Boolean =
        components(manufacturer).isNotEmpty()

    fun components(manufacturer: String = Build.MANUFACTURER): List<Pair<String, String>> {
        val key = manufacturer.lowercase(Locale.ROOT)
        return when (key) {
            in XIAOMI -> XIAOMI_COMPONENTS
            in HUAWEI -> HUAWEI_COMPONENTS
            in OPPO -> OPPO_COMPONENTS
            in VIVO -> VIVO_COMPONENTS
            else -> emptyList()
        }
    }

    fun isStale(enabled: Boolean, lastRefreshAt: Long, installedAt: Long, now: Long): Boolean {
        if (!enabled) return false
        val baseline = if (lastRefreshAt > 0L) lastRefreshAt else installedAt
        return baseline > 0L && now - baseline > STALE_AFTER_MS
    }

    fun installedAt(context: Context): Long =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrDefault(0L)

    fun candidateIntents(
        context: Context,
        manufacturer: String = Build.MANUFACTURER,
    ): List<Intent> {
        val oem = components(manufacturer).map { (pkg, cls) ->
            Intent().setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return oem + appDetails
    }

    fun resolve(
        context: Context,
        manufacturer: String = Build.MANUFACTURER,
        canHandle: (Intent) -> Boolean = { it.resolveActivity(context.packageManager) != null },
    ): Intent? = candidateIntents(context, manufacturer).firstOrNull(canHandle)

    fun open(context: Context, manufacturer: String = Build.MANUFACTURER): Boolean {
        for (intent in candidateIntents(context, manufacturer)) {
            if (intent.resolveActivity(context.packageManager) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
