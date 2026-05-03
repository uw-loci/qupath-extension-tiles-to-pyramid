pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

// TODO: Specify which version of QuPath the extension is targeting here
qupath {
    version = "0.7.0"
}

// Apply QuPath Gradle settings plugin to handle configuration.
// The Foojay toolchain resolver lets Gradle auto-download the JDK
// version qupath-conventions asks for (Java 25 for QuPath 0.7) when
// the build host doesn't have it installed -- without it, fresh
// Windows / CI machines fail with "Cannot find a Java installation
// on your machine matching: {languageVersion=25 ...}, Toolchain
// download repositories have not been configured."
plugins {
    id("io.github.qupath.qupath-extension-settings") version "0.2.1"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}