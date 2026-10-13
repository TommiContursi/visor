plugins {
    id("visor.android.library")
    id("visor.android.compose")
}

android {
    namespace = "com.macedonga.visor.core.design"
}

dependencies {
    // Material 3 components are used. Its colour and shape defaults are not: the theme
    // below replaces both, per the project rule.
    implementation(libs.androidx.compose.material3)
}
