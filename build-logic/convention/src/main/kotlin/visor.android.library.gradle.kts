import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
}

extensions.configure<LibraryExtension> {
    compileSdk = libs.int("compileSdk")

    defaultConfig {
        minSdk = libs.int("minSdk")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    "implementation"(libs.findLibrary("kotlinx-coroutines-core").get())
}
