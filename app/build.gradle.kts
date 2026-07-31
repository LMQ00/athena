plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.swipeguard.xposed"
    // libxposed:service:102.0.0 要求 compileSdk >= 37。
    // compileSdkMinor=0 → AGP 查找 platforms/android-37.0（正式包名，默认仓库可用；
    // 裸 android-37 与 CinnamonBun 预览包均不存在/已移除）。
    compileSdk = 37
    compileSdkMinor = 0


    signingConfigs {
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = project.findProperty("KEYSTORE_DEBUG_PASSWORD")?.toString() ?: "android"
            storeFile = file(rootProject.projectDir.resolve("config/signing/debug.keystore"))
            storePassword = project.findProperty("KEYSTORE_DEBUG_PASSWORD")?.toString() ?: "android"
        }
        // Release 签名通过环境变量注入（GitHub Secrets），本地构建时会回退到 debug keystore
        create("release") {
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "android"
            storeFile = file(System.getenv("RELEASE_STORE_FILE")
                ?: rootProject.projectDir.resolve("config/signing/debug.keystore").toString())
            storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "android"
        }
    }

    defaultConfig {
        applicationId = "com.swipeguard.xposed"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        disable += "Instantiatable"
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.kotlinx.serialization.json)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
