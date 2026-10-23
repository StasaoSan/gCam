// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Generated resources in a synced Desktop folder can acquire invalid " 2" names.
// In CI, keep Gradle's standard build paths expected by the release workflow.
if (System.getenv("CI") != "true") {
    val checkoutId = rootDir.canonicalPath.hashCode().toUInt().toString(16)
    val localBuildRoot = file("${System.getProperty("user.home")}/.gradle/gcam-build/$checkoutId")
    layout.buildDirectory.set(localBuildRoot.resolve("root"))
    subprojects {
        layout.buildDirectory.set(localBuildRoot.resolve(name))
    }
}
