// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// 一键构建双端产物：
// - Android：androidApp/build/outputs/apk/release/app-release.apk
// - Windows：desktopApp/build/compose/binaries/main/ 下的 exe/msi/zip
tasks.register("packageAll") {
    group = "build"
    description = "一次构建 Android 签名 APK 与 Windows MSI/ZIP/现代 EXE"
    dependsOn(
        ":androidApp:assembleRelease",
        ":desktopApp:packageMsi",
        ":desktopApp:packageZip",
        ":desktopApp:installerExe"
    )
}
