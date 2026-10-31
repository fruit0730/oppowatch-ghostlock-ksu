package me.weishu.kernelsu

import android.content.Context
import android.content.SharedPreferences
import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.weishu.kernelsu.Natives.Profile.RootProfileFlag
import me.weishu.kernelsu.ui.util.rootAvailable

/**
 * @author weishu
 * @date 2022/12/8.
 *
 * Watch (OWW212 / GhostLock jailbreak) port: this used to be a JNI facade over
 * the KernelSU kernel driver. There is no arm32 KernelSU module for this kernel,
 * so the small state surface the manager actually needs is kept in
 * SharedPreferences and served locally instead.
 */
object Natives {
    const val MINIMAL_SUPPORTED_KERNEL = 32513

    const val KERNEL_SU_DOMAIN = "u:r:ksu:s0"

    const val ROOT_UID = 0
    const val ROOT_GID = 0

    private const val UAPI_VERSION = 32513
    private const val PREFS_NAME = "jailbreak"
    private const val PROFILE_PREFIX = "profile:"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences
        get() = ksuApp.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val version: Int get() = UAPI_VERSION

    val isSafeMode: Boolean get() = false

    val isLkmMode: Boolean get() = true

    val isLkmBundled: Boolean get() = false

    val isLateLoadMode: Boolean get() = true

    val isManager: Boolean get() = true

    val isPrBuild: Boolean get() = false

    fun uidShouldUmount(uid: Int): Boolean = false

    /**
     * Get the profile of the given package.
     * @param key usually the package name
     */
    fun getAppProfile(key: String?, uid: Int): Profile {
        val name = key ?: ""
        val raw = prefs.getString(PROFILE_PREFIX + name, null) ?: return Profile(name, uid)
        return runCatching {
            json.decodeFromString(Profile.serializer(), raw).copy(currentUid = uid)
        }.getOrElse { Profile(name, uid) }
    }

    fun setAppProfile(profile: Profile?): Boolean {
        if (profile == null) return false
        prefs.edit()
            .putString(PROFILE_PREFIX + profile.name, json.encodeToString(Profile.serializer(), profile))
            .apply()
        return true
    }

    fun isSuEnabled(): Boolean = prefs.getBoolean("feature_su", true)

    fun setSuEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean("feature_su", enabled).apply()
        return true
    }

    fun isKernelUmountEnabled(): Boolean = prefs.getBoolean("feature_kernel_umount", true)

    fun setKernelUmountEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean("feature_kernel_umount", enabled).apply()
        return true
    }

    fun isSelinuxHideEnabled(): Boolean = prefs.getBoolean("feature_selinux_hide", true)

    fun setSelinuxHideEnabled(enabled: Boolean): Int {
        prefs.edit().putBoolean("feature_selinux_hide", enabled).apply()
        return 0
    }

    fun getUserName(uid: Int): String? = null

    fun getSuperuserCount(): Int {
        return prefs.all.entries.count { (key, value) ->
            key.startsWith(PROFILE_PREFIX) && value is String && runCatching {
                json.decodeFromString(Profile.serializer(), value).allowSu
            }.getOrDefault(false)
        }
    }

    private const val NON_ROOT_DEFAULT_PROFILE_KEY = "$"
    private const val NOBODY_UID = 9999

    fun setDefaultUmountModules(umountModules: Boolean): Boolean {
        Profile(
            NON_ROOT_DEFAULT_PROFILE_KEY,
            NOBODY_UID,
            false,
            umountModules = umountModules
        ).let {
            return setAppProfile(it)
        }
    }

    fun isDefaultUmountModules(): Boolean {
        getAppProfile(NON_ROOT_DEFAULT_PROFILE_KEY, NOBODY_UID).let {
            return it.umountModules
        }
    }

    val kernelUAPIVersion: Int get() = UAPI_VERSION

    val managerUAPIVersion: Int get() = UAPI_VERSION

    fun isFullFeatured(): Boolean {
        return isManager && kernelUAPIVersion == managerUAPIVersion && rootAvailable()
    }

    @Keep
    @Immutable
    @Parcelize
    @Serializable
    data class Profile(
        // and there is a default profile for root and non-root
        val name: String,
        // current uid for the package, this is convivent for kernel to check
        // if the package name doesn't match uid, then it should be invalidated.
        val currentUid: Int = 0,

        // if this is true, kernel will grant root permission to this package
        val allowSu: Boolean = false,

        // these are used for root profile
        val rootUseDefault: Boolean = true,
        val rootTemplate: String? = null,
        val uid: Int = ROOT_UID,
        val gid: Int = ROOT_GID,
        val groups: List<Int> = mutableListOf(),
        val capabilities: List<Int> = mutableListOf(),
        val context: String = KERNEL_SU_DOMAIN,
        val namespace: Int = Namespace.INHERITED.ordinal,

        val nonRootUseDefault: Boolean = true,
        val umountModules: Boolean = true,
        var rules: String = "", // this field is save in ksud!!

        val flags: Long = FLAG_KSU_NO_NEW_PRIVS,
    ) : Parcelable {
        @Keep
        enum class RootProfileFlag(val display: String, val desc: Int) {
            NO_NEW_PRIVS(
                "NO_NEW_PRIVS",
                R.string.profile_flags_desc_no_new_privs
            )
        }

        enum class Namespace {
            INHERITED,
            GLOBAL,
            INDIVIDUAL,
        }

        constructor() : this("")
    }

    const val FLAG_KSU_NO_NEW_PRIVS = 1L
}

fun List<RootProfileFlag>.toRawFlags(): Long =
    fold(0L) { acc, flag -> acc.or(1L.shl(flag.ordinal)) }

fun List<RootProfileFlag>.toOrdinalList(): List<Int> =
    map { it.ordinal }

fun Long.toRootProfileFlags(): List<RootProfileFlag> =
    RootProfileFlag.entries.filter { 1L.shl(it.ordinal).and(this) != 0L }.toList()
