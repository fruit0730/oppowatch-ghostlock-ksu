package me.weishu.kernelsu.data.repository

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.model.AppInfo
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.rootAvailable
import java.io.File

class SuperUserRepositoryImpl : SuperUserRepository {

    companion object {
        private const val TAG = "SuperUserRepository"

        // OWW212 ColorOS Watch returns 0 from getInstalledPackages()/getInstalledApplications()
        // even for a targetSdk 29 app: the ROM blocks package enumeration for third-party apps.
        // Named lookups still work, so names come from `pm list packages -U` over the
        // jailbreak root shell and every name is resolved through PackageManager.
        private val PM_LINE = Regex("""^package:(\S+)(?:\s+uid:(\d+))?.*$""")

        private val SYSTEM_APK_DIRS = listOf(
            "/system/app",
            "/system/priv-app",
            "/system_ext/app",
            "/system_ext/priv-app",
            "/product/app",
            "/product/priv-app",
            "/vendor/app",
            "/odm/app",
        )

        @Volatile
        private var systemPackageCache: List<Pair<String, Int>>? = null
    }

    override suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = ksuApp.packageManager
            val root = rootAvailable()
            val candidates = listPackageNames(pm, root)
            Log.i(TAG, "package candidates: ${candidates.size} (root=$root)")

            val apps = candidates.distinctBy { it.first }.mapNotNull { (packageName, uidHint) ->
                val info = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
                val ai = info?.applicationInfo
                if (ai != null && (ai.flags and ApplicationInfo.FLAG_HAS_CODE) == 0) {
                    return@mapNotNull null
                }
                val uid = ai?.uid ?: uidHint
                if (uid < 0) return@mapNotNull null

                val packageInfo = info ?: PackageInfo().apply {
                    this.packageName = packageName
                    applicationInfo = ApplicationInfo().apply {
                        this.packageName = packageName
                        this.uid = uid
                        flags = ApplicationInfo.FLAG_HAS_CODE
                    }
                }
                val label = runCatching { packageInfo.applicationInfo?.loadLabel(pm)?.toString() }
                    .getOrNull().orEmpty().ifEmpty { packageName }
                AppInfo(
                    label = label,
                    packageInfo = packageInfo,
                    profile = Natives.getAppProfile(packageName, uid),
                )
            }
            Log.i(TAG, "app list size: ${apps.size}")
            Pair(apps, listOf(0))
        }
    }

    override suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            if (currentApps.isEmpty()) return@runCatching emptyList()

            currentApps.map {
                val profile = Natives.getAppProfile(it.profileKey, it.uid)
                it.copy(profile = profile)
            }
        }
    }

    private fun listPackageNames(pm: PackageManager, root: Boolean): List<Pair<String, Int>> {
        if (root) {
            // ShellUtils.fastCmd only returns the first output line; a package
            // list needs the full stdout, so drive the job directly.
            val out = runLines("pm list packages -U")
            val fromRoot = out.mapNotNull { line ->
                val match = PM_LINE.find(line.trim()) ?: return@mapNotNull null
                val name = match.groupValues[1]
                val uid = match.groupValues.getOrNull(2)?.toIntOrNull() ?: -1
                name to uid
            }
            if (fromRoot.isNotEmpty()) return fromRoot

            // `pm` can fail from some roots; packages.list is 0640 system:system
            // and still readable with the jailbreak's CAP_DAC_READ_SEARCH.
            val list = runLines("cat /data/system/packages.list 2>/dev/null | awk '{print $1, $2}'")
            val fromList = list.mapNotNull { line ->
                val parts = line.trim().split(' ', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val uid = parts[1].toIntOrNull() ?: return@mapNotNull null
                parts[0] to uid
            }
            if (fromList.isNotEmpty()) return fromList
        }

        // Without root the ROM still blocks enumeration. System APKs live on
        // world-readable read-only partitions, so scan them and resolve each
        // package by name; add launcher activities for the user apps.
        val merged = LinkedHashMap<String, Int>()
        for ((name, uid) in cachedSystemPackages(pm)) merged[name] = uid

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching { pm.queryIntentActivities(launcherIntent, 0) }
            .getOrDefault(emptyList())
            .forEach { ri ->
                val ai = ri.activityInfo?.applicationInfo ?: return@forEach
                merged.putIfAbsent(ai.packageName, ai.uid)
            }
        if (merged.isNotEmpty()) return merged.map { it.key to it.value }

        return runCatching { pm.getInstalledPackages(0) }
            .getOrDefault(emptyList())
            .map { it.packageName to (it.applicationInfo?.uid ?: -1) }
    }

    @Synchronized
    private fun cachedSystemPackages(pm: PackageManager): List<Pair<String, Int>> {
        systemPackageCache?.let { return it }
        val found = LinkedHashMap<String, Int>()
        for (path in SYSTEM_APK_DIRS) {
            scanApkDir(File(path), 0, pm, found)
        }
        Log.i(TAG, "system apk scan: ${found.size}")
        return found.map { it.key to it.value }.also { systemPackageCache = it }
    }

    @Suppress("DEPRECATION")
    private fun scanApkDir(dir: File, depth: Int, pm: PackageManager, out: MutableMap<String, Int>) {
        if (depth > 4) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isDirectory) {
                scanApkDir(child, depth + 1, pm, out)
                continue
            }
            if (!child.name.endsWith(".apk", ignoreCase = true)) continue
            val archive = runCatching {
                pm.getPackageArchiveInfo(child.absolutePath, 0)
            }.getOrNull() ?: continue
            val packageName = archive.packageName ?: continue
            if (out.containsKey(packageName)) continue
            val uid = runCatching { pm.getPackageInfo(packageName, 0).applicationInfo?.uid ?: -1 }
                .getOrElse {
                    runCatching { pm.getApplicationInfo(packageName, 0).uid }.getOrElse { -1 }
                }
            if (uid >= 0) out[packageName] = uid
        }
    }

    private fun runLines(command: String): List<String> {
        return runCatching {
            getRootShell().newJob()
                .add(command)
                .to(ArrayList<String>(), ArrayList<String>())
                .exec().out
        }.getOrDefault(emptyList())
    }
}
