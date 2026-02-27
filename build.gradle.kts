plugins {
    // Support writing the extension in Groovy (remove this if you don't want to)
    groovy
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
    id("maven-publish")
    // Note: Platform detection (osdetector) is already provided by qupath-conventions
}

//Required for working with qupath-extension-qpsc in IntelliJ, allowing import statements to work
//Build this with gradle, then use publishToMavenLocal in order for imports like
//import qupath.ext.basicstitching.config.StitchingConfig;
//import qupath.ext.basicstitching.workflow.StitchingWorkflow;
//to work
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.uw-loci"
            artifactId = "qupath-extension-tiles-to-pyramid"
            version = "0.1.0"

            from(components["java"])
        }
    }
}
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.scijava.org/content/repositories/releases")
    }
    maven {
        url = uri("https://artifacts.openmicroscopy.org/artifactory/maven/")
    }
}

qupathExtension {
    name = "qupath-extension-tiles-to-pyramid"
    group = "io.github.uw-loci"
    version = "0.1.0"
    description = "Convert tiles into pyramidal OME-TIFF or OME-ZARR formats"
    automaticModule = "io.github.uw-loci.extension.tiles-to-pyramid"
}

dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // bioformats plugin needed for OMEPyramidWriter and OMEZarrWriter
    implementation("io.github.qupath:qupath-extension-bioformats:0.6.0-rc4")

    // Add Bio-Formats explicitly for compile time to avoid "class file for loci.formats.FormatException not found"
    shadow("ome:formats-gpl:7.1.0")

    // ZARR format support dependencies (explicit for compile-time resolution)
    // These are needed for OME-ZARR output format functionality
    implementation("dev.zarr:jzarr:0.4.2")

    // If you aren't using Groovy, this can be removed
    shadow(libs.bundles.groovy)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)

}
