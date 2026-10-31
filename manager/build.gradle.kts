plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 29
extra["androidTargetSdkVersion"] = 29
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "36.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
extra["managerVersionCode"] = getVersionCode()
extra["managerVersionName"] = getVersionName()

fun getGitCommitCount(): Int {
    return runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
        process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
    }.getOrDefault(1)
}

fun getGitDescribe(): String {
    val describe = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--always"))
        process.inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrDefault("")
    // No git checkout (or git failed): exec() still "succeeds" with empty output,
    // and an empty versionName makes PackageInfo.versionName null -> crash.
    return describe.ifBlank { "watch-1.0" }
}

fun getVersionCode(): Int {
    val commitCount = getGitCommitCount()
    return 30000 + commitCount
}

fun getVersionName(): String {
    return getGitDescribe()
}
