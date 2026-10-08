plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi) // JVM 截图回归（Robolectric 渲染，无需设备）
}

android {
    namespace = "com.qaqh.harness"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qaqh.harness"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0-beta.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.haze) // 玻璃材质：hazeSource 采集内容
    implementation(libs.haze.blur) // 磨砂玻璃：hazeBlur（输入栏等）
    implementation(libs.haze.blur.material3) // HazeBlurStyle.Material3 预设
    implementation(libs.haze.glass) // 液态玻璃：hazeGlass（折射/高光 shader，@ExperimentalHazeApi）
    implementation(libs.haze.glass.material3) // GlassStyle.Material3 预设
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.metrics.performance) // JankStats：流式渲染逐帧耗时采集（debug 门控）
    implementation(libs.markdown.renderer.core) // Markdown：sealed 文本块定稿渲染
    implementation(libs.markdown.renderer.m3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    // Roborazzi 截图回归：JVM（Robolectric）渲染 Compose，出 PNG 基线防视觉回归
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
