plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    "implementation"(libs.findLibrary("kotlinx-coroutines-core").get())
}
