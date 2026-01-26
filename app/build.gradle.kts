plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "top.littlew.npn"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.littlew.npn"
        minSdk = 36
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        release {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
