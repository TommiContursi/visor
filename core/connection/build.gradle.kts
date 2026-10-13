plugins {
    id("visor.android.library")
}

android {
    namespace = "com.macedonga.visor.core.connection"
}

dependencies {
    // api rather than implementation: this module's public API is made of the layers below it.
    api(project(":core:identity"))
}
