plugins {
    // Support writing the extension in Groovy (remove this if you don't want to)
    groovy
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
    id("maven-publish")
    // Auto-formatting (palantirJavaFormat) -- gates the build via `check`
    id("com.diffplug.spotless") version "7.0.2"
    // Static bug detection
    id("com.github.spotbugs") version "6.5.0"
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
            version = "0.6.5"

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
    version = "0.6.5"
    description = "Convert tiles into pyramidal OME-TIFF or OME-ZARR formats"
    automaticModule = "io.github.uw-loci.extension.tiles-to-pyramid"
}

dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    // Gson is used by MicroManagerMetadataStrategy to parse MMStack metadata files
    shadow(libs.gson)

    // Bio-Formats plugin, for OMEPyramidWriter and OMEZarrWriter.
    //
    // PROVIDED, not bundled, and version-matched to the QuPath we run inside: QuPath 0.7.0 ships
    // qupath-extension-bioformats 0.7.0 in its own lib/. Bundling our own copy (it was
    // implementation("...:0.6.0-rc4")) meant shipping an OLDER duplicate of an extension QuPath
    // already provides -- the same duplicate-class hazard the shadow(...) entries above exist to
    // avoid -- and it dragged in ~100 MB of transitives.
    shadow("io.github.qupath:qupath-extension-bioformats:0.7.0")

    // Add Bio-Formats explicitly for compile time to avoid "class file for loci.formats.FormatException not found"
    shadow("ome:formats-gpl:8.5.0")

    // OME-ZARR support. PROVIDED for the same reason: QuPath ships jzarr 0.4.2 (the identical
    // version) plus jblosc and the platform-correct blosc native in its lib/.
    //
    // Bundling this was actively WRONG, not merely wasteful. The blosc native is selected by the
    // BUILD machine's OS, so releases (built on ubuntu-latest) shipped linux-x86-64/libblosc.so --
    // and no blosc.dll -- to Windows users. It only ever worked because QuPath's own install
    // supplies the right one for the platform it is installed on. Taking it as provided means the
    // native always matches the host, by construction.
    shadow("dev.zarr:jzarr:0.4.2")

    // If you aren't using Groovy, this can be removed
    shadow(libs.bundles.groovy)

    // For testing.
    //
    // The shadow(...) configuration is compile-only by design, so anything provided at runtime by
    // the QuPath application has to be re-declared here or the tests cannot load it -- there is no
    // QuPath install behind a Gradle test JVM. That is why libs.bundles.qupath is repeated below,
    // and why bioformats/jzarr must be too now that they are provided rather than bundled.
    testImplementation(libs.bundles.qupath)
    testImplementation("io.github.qupath:qupath-extension-bioformats:0.7.0")
    testImplementation("dev.zarr:jzarr:0.4.2")
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
// StitchBenchmarkTest is opt-in: it is skipped unless -PstitchBench is passed, so
// the normal suite stays fast. See that class for the available knobs.
//   ./gradlew test --tests "*StitchBenchmarkTest*" -PstitchBench
tasks.test {
    val benchEnabled = project.hasProperty("stitchBench")
    systemProperty("stitchBench", benchEnabled.toString())
    for (knob in listOf("stitchBenchGrid", "stitchBenchTile", "stitchBenchReps", "stitchBenchQuPathCacheMb")) {
        project.findProperty(knob)?.let { systemProperty(knob, it.toString()) }
    }
    // Lower this (e.g. -PstitchBenchHeap=256m) to prove the bounded-memory envelope.
    maxHeapSize = (project.findProperty("stitchBenchHeap") ?: "2g").toString()
    if (benchEnabled) {
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false } // always re-run; a cached benchmark is not a measurement
    }
}

// ---------------------------------------------------------------------------
// Spotless -- auto-formatting (gates the build via `check`)
// ---------------------------------------------------------------------------
spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.90.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---------------------------------------------------------------------------
// SpotBugs -- static bug detection (gates the build)
// ---------------------------------------------------------------------------
spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
}

// ---------------------------------------------------------------------------
// Test logging -- print the actual cause of a failure
// ---------------------------------------------------------------------------
// Gradle's default prints only the exception type and the assertion line, which is useless for
// anything that fails from a cause rather than an assert. A Windows-only UnsatisfiedLinkError in
// CI was unreadable for exactly this reason: the log said "UnsatisfiedLinkError at line 171" and
// nothing about which library or why. Most of this project's tests only ever run on Windows in
// CI, where nobody can attach a debugger, so the log IS the diagnosis.
tasks.withType<Test> {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
        showExceptions = true
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21) // QuPath 0.7 runs on Java 21; pin bytecode target so any build JDK emits loadable classes
}
// QuPath 0.7.0's maven artifacts are published as requiring JVM 25 (org.gradle.jvm.version=25),
// even though the QuPath app runs on Java 21. options.release=21 makes Gradle resolve a
// JVM-21-compatible classpath, which then rejects those JVM-25 artifacts on a clean build. Force
// the resolvable classpaths to request JVM 25 so the deps resolve; bytecode target (21) is
// unaffected, so the jar still loads on Java 21. (Upstream QuPath metadata bug; remove if fixed.)
configurations.configureEach {
    if (isCanBeResolved) {
        attributes {
            attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}
