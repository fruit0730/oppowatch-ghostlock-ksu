package me.weishu.kernelsu

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.system.Os
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Locale

lateinit var ksuApp: KernelSUApplication

class KernelSUApplication : Application(), ViewModelStoreOwner {

    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }

    lateinit var okhttpClient: OkHttpClient
    private val appViewModelStore by lazy { ViewModelStore() }

    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked == true

    override fun onCreate() {
        super.onCreate()
        ksuApp = this

        if (!isUserUnlocked()) {
            return
        }

        installKsudShim()
        showSystemAppsByDefault()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val enable = SettingsRepositoryImpl().enablePredictiveBack
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }

        val superUserViewModel = ViewModelProvider(this)[SuperUserViewModel::class.java]
        superUserViewModel.loadAppList()

        val webroot = File(dataDir, "webroot")
        if (!webroot.exists()) {
            webroot.mkdir()
        }

        // Provide working env for rust's temp_dir()
        Os.setenv("TMPDIR", cacheDir.absolutePath, true)

        okhttpClient =
            OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "KernelSU/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                    )
                }.build()
    }

    private fun installKsudShim() {
        runCatching {
            val target = File(filesDir, "ksud-shim.sh")
            assets.open("ksud-shim.sh").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            // The root shell reads this file as another uid; keep it world readable.
            target.setReadable(true, false)
        }.onFailure {
            Log.w("KernelSU", "failed to install ksud shim", it)
        }
    }

    private fun showSystemAppsByDefault() {
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        if (settings.getBoolean("watch_show_system_apps_v1", false)) return
        // The ROM hides package enumeration, so the manager falls back to
        // scanning system APKs; show those entries instead of filtering them.
        settings.edit()
            .putBoolean("show_system_apps", true)
            .putBoolean("watch_show_system_apps_v1", true)
            .apply()
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
