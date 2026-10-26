import java.util.Properties

plugins {
    id("com.android.application")
}

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "fi.tomba.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "fi.tomba.companion"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // '0' works only with Developer Mode and is NOT sufficient for Beta voice launch.
        manifestPlaceholders["mwdat_application_id"] = local.getProperty("mwdat_application_id", "0")
        manifestPlaceholders["mwdat_client_token"] = local.getProperty("mwdat_client_token", "0")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.meta.wearable:mwdat-core:1.0.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
}
