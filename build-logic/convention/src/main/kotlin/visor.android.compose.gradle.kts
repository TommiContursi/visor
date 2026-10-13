import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

// Configured by concrete extension type rather than through CommonExtension, whose generic
// arity has changed between AGP versions and would pin this file to one of them.
extensions.findByType(ApplicationExtension::class.java)?.buildFeatures?.compose = true
extensions.findByType(LibraryExtension::class.java)?.buildFeatures?.compose = true

dependencies {
    val bom = libs.findLibrary("androidx-compose-bom").get()
    "implementation"(platform(bom))

    "implementation"(libs.findLibrary("androidx-compose-ui").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-graphics").get())
    "implementation"(libs.findLibrary("androidx-compose-foundation").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-tooling-preview").get())
    "debugImplementation"(libs.findLibrary("androidx-compose-ui-tooling").get())

}
