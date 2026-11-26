plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

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
         * 这里取 CI 的 run number（GitHub Actions 每次递增，不会重复）：
         *   · 本地构建没有该环境变量 -> 回退到 1
         *   · 手搓个包给手机装时，用 -PversionCodeOverride=123 覆盖
         *
         * 注意不用时间戳：versionCode 是 Int，毫秒时间戳会溢出；
         * 秒级时间戳虽不溢出但会让"最新版"难以判断。
         */
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
            ?: (project.findProperty("versionCodeOverride") as String?)?.toIntOrNull()
            ?: 1)
        versionName = "1.0.${versionCode}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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
