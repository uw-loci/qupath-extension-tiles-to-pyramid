/*
 * Diagnostic harness: does concurrent OME-TIFF stitching corrupt outputs
 * under a given compression codec?
 *
 * Background:
 *   PyramidImageWriter serializes all OME-TIFF writes with a JVM-wide
 *   Semaphore(1) because two parallel stitches once produced silent J2K
 *   garbage on multi-angle PPM acquisitions. We did not isolate whether
 *   the hazard is codec-specific (JAI J2K singleton in IIORegistry) or
 *   present for any compression (BioFormats writer internals). This script
 *   bypasses the gate and reproduces concurrent writes directly so we can
 *   tell which compressions are safe.
 *
 * Usage:
 *   1. Acquire a tile directory with the "keep tiles" option enabled.
 *      The directory should contain one or more subfolders that each hold
 *      a TileConfiguration.txt and a set of .tif tiles.
 *   2. Edit the constants below to point at that data.
 *   3. Run from QuPath: Automate -> Show script editor -> open this file -> Run.
 *   4. Read the per-compression results table at the end. A codec is
 *      "concurrent-safe" only if every parallel write opens cleanly AND
 *      reads bytewise-identical to the reference (sequential, gate-on)
 *      output.
 */

import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferUShort
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import qupath.ext.basicstitching.assembly.PyramidImageWriter
import qupath.ext.basicstitching.assembly.direct.DirectTileStitcher
import qupath.ext.basicstitching.config.StitchingConfig
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy
import qupath.ext.basicstitching.stitching.TileMapping
import qupath.lib.images.servers.ImageServer
import qupath.lib.images.servers.ImageServers
import qupath.lib.regions.RegionRequest

// ---- USER CONFIG -----------------------------------------------------------

// Folder that contains one or more tile subdirectories (each with
// TileConfiguration.txt + tile .tif files). Use the same folder you would
// pass to the normal stitching GUI.
def TILE_ROOT = '/PATH/TO/ACQUISITION/ROOT'

// Where to drop the test output files. A subfolder gets created here.
def OUTPUT_DIR = '/PATH/TO/CONCURRENT_WRITE_TEST_OUTPUT'

// Subdirectory name substring to stitch. "" matches all; for multi-angle
// PPM data, use one angle name (e.g. "_0_deg") so every parallel write
// produces an output of the same shape.
def MATCHING_STRING = ''

// Compressions to test. Each is a string accepted by
// UtilityFunctions.getCompressionType. "J2K_LOSSY" is the historical
// default; "LZW" is the suspected safer alternative.
def COMPRESSIONS = ['LZW', 'J2K_LOSSY']

// How many writes to launch in parallel per trial.
def PARALLELISM = 4

// How many trials per compression. Concurrency bugs are probabilistic --
// the original ratio was about every-other-task, so >=4 trials per
// compression is reasonable.
def TRIALS = 4

// Pixel size and Z spacing in microns. Pull these from your acquisition's
// metadata; they only affect file metadata, not the bytes we compare.
def PIXEL_SIZE_UM = 0.5
def Z_SPACING_UM = 1.0

// Where in the level-0 image to sample pixels for the integrity check.
// Pick three points that should be inside the stitched area; outputs that
// silently corrupted will return garbage or all-zero bytes here.
def SAMPLE_POINTS = [[1024, 1024, 256, 256], [4096, 4096, 256, 256], [8192, 8192, 256, 256]]

// ---- END USER CONFIG -------------------------------------------------------

def log = { String msg -> println "[concurrent-writes] ${msg}" }

// 1. Parse mappings once.
def strategy = new TileConfigurationTxtStrategy()
def mappings = strategy.prepareStitching(
        TILE_ROOT, PIXEL_SIZE_UM, 1.0, MATCHING_STRING)
if (!mappings || mappings.isEmpty()) {
    log "No mappings parsed from ${TILE_ROOT} matching '${MATCHING_STRING}'. Aborting."
    return
}

// Take only the first subdirectory so every parallel write is stitching the
// same input. (If multiple subdirs match, threads racing on different inputs
// would muddy the diagnostic.)
def firstSubdir = mappings[0].subdirName
def mappingsFirst = mappings.findAll { it.subdirName == firstSubdir }
log "Parsed ${mappings.size()} total mappings; using ${mappingsFirst.size()} from subdir '${firstSubdir}'"

// Prepare output directory.
def outBase = Paths.get(OUTPUT_DIR, "concurrent_write_test_${System.currentTimeMillis()}")
Files.createDirectories(outBase)
log "Output dir: ${outBase}"

def sha256 = { byte[] bytes ->
    def md = MessageDigest.getInstance("SHA-256")
    def hex = md.digest(bytes).encodeHex().toString()
    return hex.substring(0, 16)  // short hash for readable output
}

// Extract the raw bytes of a BufferedImage's underlying buffer so the hash
// is invariant under color model presentation.
def imageBytes = { BufferedImage img ->
    def db = img.raster.dataBuffer
    def baos = new java.io.ByteArrayOutputStream()
    if (db instanceof DataBufferByte) {
        db.bankData.each { baos.write(it) }
    } else if (db instanceof DataBufferUShort) {
        db.bankData.each { short[] bank ->
            def bb = java.nio.ByteBuffer.allocate(bank.length * 2)
            bb.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(bank)
            baos.write(bb.array())
        }
    } else {
        // Fallback: render to byte stream as PNG. Slower but type-safe.
        javax.imageio.ImageIO.write(img, "png", baos)
    }
    return baos.toByteArray()
}

def verifyOutput = { Path file ->
    def result = [opened: false, sampleHashes: [], error: null]
    ImageServer<BufferedImage> server = null
    try {
        server = ImageServers.buildServer(file.toUri())
        result.opened = true
        SAMPLE_POINTS.each { sp ->
            int x = sp[0], y = sp[1], w = sp[2], h = sp[3]
            int xClamped = Math.min(x, server.getWidth() - w)
            int yClamped = Math.min(y, server.getHeight() - h)
            if (xClamped < 0 || yClamped < 0) {
                result.sampleHashes << "OOB(${x},${y})"
                return
            }
            def req = RegionRequest.createInstance(server.getPath(), 1.0, xClamped, yClamped, w, h)
            def img = server.readRegion(req)
            result.sampleHashes << sha256(imageBytes(img))
        }
    } catch (Throwable t) {
        result.error = "${t.class.simpleName}: ${t.message}"
    } finally {
        if (server != null) try { server.close() } catch (Exception ignored) {}
    }
    return result
}

def runConcurrentWrites = { String comp, int parallelism, int trial ->
    def config = new StitchingConfig(
            'Coordinates in TileConfiguration.txt file',  // stitchingType (unused by DirectTileStitcher)
            TILE_ROOT,                                    // folderPath (unused here)
            outBase.toString(),                           // outputPath
            comp,                                         // compressionType -- the variable under test
            PIXEL_SIZE_UM,
            1.0,                                          // baseDownsample
            MATCHING_STRING,                              // matchingString (unused after mappings parsed)
            Z_SPACING_UM,
            0.0,                                          // xFudgeFactor
            0.0,                                          // yFudgeFactor
            StitchingConfig.OutputFormat.OME_TIFF)

    def pool = Executors.newFixedThreadPool(parallelism)
    def start = new CountDownLatch(1)
    def trialResults = Collections.synchronizedList([])

    def futures = (0..<parallelism).collect { idx ->
        pool.submit({
            String name = "trial${trial}_${comp}_${idx}"
            def tStart = System.currentTimeMillis()
            try {
                start.await()
                String written = DirectTileStitcher.stitch(
                        mappingsFirst, outBase.toString(), name, config, null)
                long elapsed = System.currentTimeMillis() - tStart
                trialResults << [name: name, written: written, elapsed: elapsed, error: null]
            } catch (Throwable t) {
                long elapsed = System.currentTimeMillis() - tStart
                trialResults << [name: name, written: null, elapsed: elapsed,
                                 error: "${t.class.simpleName}: ${t.message}"]
            }
        } as Runnable)
    }
    start.countDown()
    pool.shutdown()
    pool.awaitTermination(60, TimeUnit.MINUTES)
    return trialResults
}

// 2. Reference write: gate ON, single thread. This is our known-good baseline.
log "=== Reference write (gate ON, sequential) ==="
PyramidImageWriter.setTiffGateBypassedForTesting(false)
def referenceHashes = [:]  // compression -> list of sample hashes
COMPRESSIONS.each { comp ->
    log "Reference for ${comp}..."
    def writes = runConcurrentWrites(comp, 1, -1)
    def w = writes[0]
    if (w.written == null) {
        log "  reference write FAILED for ${comp}: ${w.error}"
        referenceHashes[comp] = null
    } else {
        def v = verifyOutput(Paths.get(w.written))
        if (!v.opened) {
            log "  reference output failed to open: ${v.error}"
            referenceHashes[comp] = null
        } else {
            referenceHashes[comp] = v.sampleHashes
            log "  reference for ${comp}: ${v.sampleHashes}"
        }
    }
}

// 3. Concurrent trials: gate OFF.
log ""
log "=== Concurrent trials (gate OFF, parallelism=${PARALLELISM}) ==="
PyramidImageWriter.setTiffGateBypassedForTesting(true)
def allResults = [:]  // compression -> list of trial outcomes
try {
    COMPRESSIONS.each { comp ->
        log "--- Compression: ${comp} ---"
        def compResults = []
        (0..<TRIALS).each { trial ->
            log "  trial ${trial + 1}/${TRIALS}..."
            def writes = runConcurrentWrites(comp, PARALLELISM, trial)
            writes.each { w ->
                if (w.written == null) {
                    compResults << [name: w.name, status: 'WRITE_FAILED',
                                    detail: w.error, elapsedMs: w.elapsed]
                } else {
                    def v = verifyOutput(Paths.get(w.written))
                    if (!v.opened) {
                        compResults << [name: w.name, status: 'OPEN_FAILED',
                                        detail: v.error, elapsedMs: w.elapsed]
                    } else {
                        def ref = referenceHashes[comp]
                        def match = (ref != null && ref == v.sampleHashes)
                        compResults << [name: w.name,
                                        status: match ? 'OK' : 'PIXEL_MISMATCH',
                                        detail: match ? v.sampleHashes.toString() :
                                                "got=${v.sampleHashes} ref=${ref}",
                                        elapsedMs: w.elapsed]
                    }
                }
            }
        }
        allResults[comp] = compResults
    }
} finally {
    PyramidImageWriter.setTiffGateBypassedForTesting(false)
}

// 4. Summary.
log ""
log "===== SUMMARY ====="
COMPRESSIONS.each { comp ->
    def results = allResults[comp] ?: []
    def total = results.size()
    def ok = results.count { it.status == 'OK' }
    def writeFails = results.count { it.status == 'WRITE_FAILED' }
    def openFails = results.count { it.status == 'OPEN_FAILED' }
    def pixelFails = results.count { it.status == 'PIXEL_MISMATCH' }
    def verdict = (ok == total) ? "CONCURRENT-SAFE" : "HAZARD DETECTED"
    log "${comp}: ${verdict}  (ok=${ok}/${total}, write_failed=${writeFails}, open_failed=${openFails}, pixel_mismatch=${pixelFails})"
    if (ok != total) {
        results.findAll { it.status != 'OK' }.each { r ->
            log "  - ${r.name} [${r.status}] ${r.detail}"
        }
    }
}
log ""
log "Outputs left at: ${outBase}"
log "Done."
