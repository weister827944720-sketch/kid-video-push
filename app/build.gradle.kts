plugins {
    id("com.android.application")
}

android {
    namespace = "com.kidvideopush.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kidvideopush.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 13
        versionName = "0.1.13"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
