plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.riheng.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.riheng.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 28
        versionName = "2.1.1"
    }

    // 固定签名：每次构建（包括 GitHub Actions）都用同一把 key，
    // 新 APK 才能直接覆盖安装旧版本，打卡数据不会因为重装而丢失。
    signingConfigs {
        create("riheng") {
            storeFile = file("riheng.jks")
            storePassword = "riheng-ka"
            keyAlias = "riheng"
            keyPassword = "riheng-ka"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("riheng")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("riheng")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
