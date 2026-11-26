import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * 本机构建的 versionCode 基准。
 *
 * 取 1000 是因为它在用过的 CI run number（曾到 109）之上 ——
 * Android 只允许**递增**安装，本地包的版本号必须比之前装过的 CI 包大，
 * 否则会报 INSTALL_FAILED_VERSION_DOWNGRADE。
 *
 * 必须在 `android { }` **之前**声明：`defaultConfig` 里要用它，
 * Kotlin 脚本按顺序求值，写在后面会报未定义。
 */
val LOCAL_VERSION_BASE = 1000

android {
    namespace = "at.cwiesner.android.visualtimer.ext"
    compileSdk = 35

    defaultConfig {
        applicationId = "at.cwiesner.android.visualtimer.ext"
        minSdk = 26
        targetSdk = 35
        /**
         * versionCode 必须**每次构建都递增**，否则装不上。
         *
         * 之前写死 `1`，结果是每次出新包都只能「先卸载再安装」——
         * Android 对同版本号的覆盖安装会报 INSTALL_FAILED_ALREADY_EXISTS。
         *
         * 取值优先级：
         *   1. `-PversionCodeOverride=123` —— 手动指定
         *   2. 本机编译：用**固定基准 + 当前分钟数**，保证单调递增且不会溢出
         *      （基准 1000 取自在用过的 CI run number 之上，避免新旧包比较时倒退）
         *   3. CI：GITHUB_RUN_NUMBER
         *   4. 兜底 1
         *
         * 不用毫秒时间戳（Int 会溢出），也不用纯秒级（数值很大、和其他来源不可比）。
         */
        versionCode = (project.findProperty("versionCodeOverride") as String?)?.toIntOrNull()
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
            ?: LOCAL_VERSION_BASE + ((System.currentTimeMillis() / 60_000L) % 1_000_000L).toInt()
        versionName = "1.0.${versionCode}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * 本地编译用**固定** debug keystore。
     *
     * 为什么需要：AGP 默认在 `~/.android/debug.keystore` 现场生成证书，
     * 而 CI 的每次 runner 都是干净机器 —— 每个 CI 包证书都不同，装不上只能卸载。
     * 本机固定 keystore 之后，本地出的包签名完全一致，永远可以 `adb install -r` 覆盖。
     *
     * 路径通过 local.properties（已 gitignore）传入，**不写死、不进版本库**；
     * 文件不存在时（例如 CI 环境）就跳过，退回 AGP 的默认行为，
     * 这样 CI 仍然能正常出包。
     *
     * 注意：这里**不能**写 `java.util.Properties()` —— 本文件的 `namespace` 是
     * `at.cwiesner.android.visualtimer.ext`，`java` 前缀在脚本里解析不到。
     * 用 `Properties` + import 即可。
     */
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    val keystorePath = if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { stream -> localProps.load(stream) }
        localProps.getProperty("vte.keystore.path")
    } else {
        null
    }
    val localKeystore: File? = keystorePath?.let { File(it) }?.takeIf { it.exists() }

    signingConfigs {
        // 只有本机配置了 keystore 路径时才创建这个 config。
        // CI 上没有 local.properties，所以不会走到这里，debug 包仍用 AGP 默认证书。
        if (localKeystore != null) {
            create("localDebug") {
                storeFile = localKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (localKeystore != null) {
                signingConfig = signingConfigs.getByName("localDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    /**
     * 单元测试时 `android.util.Log` 等 Android API 默认会抛
     * `RuntimeException: Method ... not mocked`。
     * 我们的 llm/ 层用了 Log 记日志，测试只要碰到就会炸。
     * 返回默认值（Log 变成空操作）最省事，也不会让测试验证到日志内容。
     */
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)

    // 单元测试：纯 JVM，跑在 CI 里（本机没有 JDK）。
    // 这些测试是这台机器上唯一能"真跑"的验证手段 —— 手势/界面只能装机试。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
