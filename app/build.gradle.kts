plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.huawei.kern_stabiliser"
    compileSdk = 29

    defaultConfig {
        applicationId = "com.huawei.kern_stabiliser"
        minSdk = 29
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }

        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    //TODO
    // implementation("androidx.core:core-ktx:1.12.0")
    // implementation("androidx.appcompat:appcompat:1.6.1")
    // implementation("com.google.android.material:material:1.11.0")
    // TODO remove?
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1")

    implementation("com.google.android.gms:play-services-location:21.1.0")
}