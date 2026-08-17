import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // AGP 9 的 KMP Android 库配置（Kotlin 2.3 起用 android 替代旧的 androidLibrary）
    android {
        namespace = "cn.novelmaker.wg1337.shared"
        compileSdk = 36
        minSdk = 24
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // 用 api 暴露给 androidApp/desktopApp，避免两端各引一套 Compose 版本
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)
                api(compose.materialIconsExtended)
                api(libs.androidx.lifecycle.viewmodel.compose)
                api(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        // Android 与桌面 JVM 共享的中间源码集：
        // 两端都是 JVM，可在这里使用 java.io / java.net / javax.crypto / org.json 等 JVM API，
        // 只有真正的平台差异才下沉到 androidMain / desktopMain。
        val jvmSharedMain by creating {
            dependsOn(commonMain)
            dependencies {
                // Android 自带 org.json，桌面 JVM 通过该依赖补齐
                implementation(libs.org.json)
            }
        }

        val androidMain by getting {
            dependsOn(commonMain)
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.appcompat)
                implementation(libs.androidx.activity.ktx)
            }
        }

        val desktopMain by getting {
            dependsOn(commonMain)
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                // Windows DPAPI（CryptProtectData / CryptUnprotectData）
                implementation(libs.jna)
                implementation(libs.jna.platform)
                // 桌面端 Dispatchers.Main（AWT/EDT）
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}
