plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// 自动生成持久化 debug keystore（统一签名，避免每次重装卸载）
val debugKeystore = rootProject.file("debug.keystore")
if (!debugKeystore.exists()) {
    val keytool = "${System.getProperty("java.home")}/bin/keytool"
    project.exec {
        commandLine(
            keytool, "-genkey", "-v",
            "-keystore", debugKeystore.absolutePath,
            "-alias", "swipeguard",
            "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000",
            "-storepass", "android",
            "-keypass", "android",
            "-dname", "CN=SwipeGuard Debug, O=SwipeGuard, C=US"
        )
    }
    logger.lifecycle("Created debug keystore at \${debugKeystore.absolutePath}")
}

android {
    namespace = "com.swipeguard.xposed"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.swipeguard.xposed"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("persistent") {
            val keystoreFile = rootProject.file("debug.keystore")
            keyAlias = "swipeguard"
            keyPassword = "android"
            storePassword = "android"
            storeFile = keystoreFile
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("persistent")
        }
        release {
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
