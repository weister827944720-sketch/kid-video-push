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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
