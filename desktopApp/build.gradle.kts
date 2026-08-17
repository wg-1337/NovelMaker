import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// 是否在安装时添加桌面快捷方式：可通过 -PdesktopShortcut=false 关闭
val desktopShortcut = (project.findProperty("desktopShortcut") as String?)?.toBoolean() ?: true

kotlin {
    // 不强制要求本机安装特定 JDK（避免触发工具链下载）；
    // 使用 IDE/Gradle 当前 JVM 编译，字节码目标设为 17。
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 与 Kotlin 保持一致的 JVM 目标，避免 compileJava(21) 与 compileKotlin(17) 冲突
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // 桌面端 Dispatchers.Main（AWT/EDT）
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "cn.novelmaker.wg1337.MainKt"

        // 降低内存占用：限制堆/元空间、串行 GC、软件渲染（省 GPU 缓冲）
        jvmArgs(
            "-Xmx512m",
            "-Xms32m",
            "-XX:MaxMetaspaceSize=160m",
            "-XX:CompressedClassSpaceSize=64m",
            "-XX:ReservedCodeCacheSize=64m",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=25",
            "-XX:+UseSerialGC",
            "-Dskiko.renderApi=SOFTWARE"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "novelmaker"
            packageVersion = "1.6.0"
            description = "轻量级小说创作工具 - AI 辅助写作"
            vendor = "wg-1337"

            windows {
                menuGroup = "novelmaker"
                iconFile.set(project.file("src/main/resources/novelmaker.ico"))
                // 安装时允许用户自定义安装位置
                dirChooser = true
                // 桌面快捷方式（可用 -PdesktopShortcut=false 关闭）
                shortcut = desktopShortcut
                // 开始菜单快捷方式
                menu = true
                // 后续发正式版本时可增加 upgradeUuid 用于 MSI 自动升级
            }
        }
    }
}

// 免安装 ZIP：直接打包 createDistributable 生成的应用目录
tasks.register<Zip>("packageZip") {
    group = "package"
    description = "打包免安装 ZIP（绿色版）"
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app/novelmaker"))
    archiveFileName.set("novelmaker-1.6.0.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/zip"))
}

// 现代安装 EXE：调用 Inno Setup 6（中文界面）
tasks.register<Exec>("installerExe") {
    group = "package"
    description = "用 Inno Setup 生成现代安装 EXE（中文界面，可选安装位置和桌面快捷方式）"
    dependsOn("createDistributable")
    val iscc = System.getenv("ISCC") ?: "C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe"
    doFirst {
        val f = file(iscc)
        if (!f.exists()) {
            throw GradleException("未找到 Inno Setup: $iscc\n请安装 Inno Setup 6，或设置环境变量 ISCC 指向 ISCC.exe")
        }
        // Inno Setup 不会自动创建输出目录，先建好避免“系统找不到指定的路径”
        project.file("build/compose/binaries/main/exe").mkdirs()
    }
    commandLine(iscc, rootProject.file("packaging/windows/installer.iss").absolutePath)
}
