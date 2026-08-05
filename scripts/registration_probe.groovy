/**
 * Tile-registration diagnostic probe.
 *
 * Runs registration on an existing tile folder and reports what every gate actually saw, WITHOUT
 * stitching. Takes seconds, not minutes -- stitching is the slow part and is not involved.
 *
 * WHY THIS EXISTS
 * Every registration threshold (match confidence, search bound, low-texture gate, ambiguity ratio)
 * was originally chosen by reasoning about what ought to be true of microscope tiles. That guesswork
 * was wrong three separate times on real data. This script turns each default from an assumption
 * into a distribution you can read.
 *
 * HOW TO RUN
 *   1. Acquire with Tile Handling = None, so the tiles survive the stitch.
 *   2. QuPath -> Automate -> Script editor -> paste this -> set FOLDER below -> Run.
 *      (Or headless: QuPath script --args "<folder>" registration_probe.groovy)
 *   3. Read the console; a per-edge CSV lands next to the tiles and can be deleted afterwards.
 *
 * WHAT TO READ
 *   maxShiftFrac / minNcc / minCoV      confirms the Preferences are being picked up
 *   rejects: [...]                      which gate is doing the rejecting
 *   TARGET EDGE                         one seam in detail: measured vs what the solve applied
 *   EDGES AT SEARCH BOUND               non-zero means the search window is clipping real shifts
 *   min texture by outcome              the low-texture gate's real distribution (run this over
 *                                       BLANK tiles as well as tissue to calibrate minCoV)
 *
 * Requires tiles-to-pyramid 0.6.4+ (per-edge diagnostics) and 0.6.2+ (preferences).
 * Every line is kept short and free of GString interpolation, because long lines get mangled when
 * pasted into the script editor and fail with "token recognition error".
 */

import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy
import qupath.ext.basicstitching.registration.*
import qupath.ext.basicstitching.utilities.RegistrationPreferences

// ---- configure ------------------------------------------------------------
def FOLDER = "D:\\2025QPSC\\data\\MH_Slides\\MH_PPM\\ppm_20x_9\\66505_58782"
double PIXEL_SIZE = 0.1725
// Which subdirectory to register. Name ONE angle -- "." scans every subdirectory, which on a
// 4-angle acquisition reads 4x the tiles (8700 instead of 2175) and spends most of the run in the
// dimension scan for three angles it then throws away. Run the script once per angle instead:
// the per-edge measurements are NOT the same across angles even though the stage positions are,
// and comparing them is the point.
def SUBDIR = "-7.0"
// Optional: name two tiles to inspect one seam in detail. Leave blank to skip.
// Pick a seam you can SEE failing in the stitched image -- that is the only way to tell a bad
// measurement (NCC found the wrong peak) from a bad solve (measurement right, placement wrong).
def TILE_A = "165.tif"
def TILE_B = "261.tif"
// Tiles to dump in full: every edge touching them, plus their solved correction. Use this when you
// can point at a BAD TILE rather than a bad edge -- a tile whose edges were all rejected got its
// position diffused from neighbours rather than measured, which is invisible in the per-edge view.
def TILES = ["44.tif", "45.tif", "160.tif", "161.tif", "260.tif", "261.tif", "165.tif", "166.tif"]
// ---------------------------------------------------------------------------

def folder = (args && args.size() > 0) ? args[0] : FOLDER
double pixelSize = (args && args.size() > 1) ? (args[1] as double) : PIXEL_SIZE
def subdir = (args && args.size() > 2) ? args[2] : SUBDIR

def strategy = new TileConfigurationTxtStrategy()
def mappings = strategy.prepareStitching(folder, pixelSize, 1.0, subdir)
if (mappings.isEmpty()) {
    println "ERROR: no tile mappings. Check FOLDER and that TileConfiguration.txt is present."
    return
}

def bySub = mappings.groupBy { it.subdirName }
def refName = bySub.max { it.value.size() }.key
def byName = [:]
bySub[refName].each { m ->
    byName.computeIfAbsent(m.file.getName(), { n ->
        def r = m.region
        new TileNode(n, m.file, (double) r.getX(), (double) r.getY(), r.getWidth(), r.getHeight())
    })
}
def nodes = new ArrayList<TileNode>(byName.values())

def settings = RegistrationPreferences.toSettings()
println "--- settings in effect ---"
println "maxShiftFrac = " + settings.maxStepErrorFrac()
println "minShiftPx   = " + settings.minStepErrorPx()
println "minNcc       = " + settings.minNcc()
println "minCoV       = " + settings.minCoeffOfVar()
println "ambiguity    = " + settings.ambiguityRatio()

def result = TileRegistrationEngine.register(new RegistrationRequest(refName, nodes, settings))
println "--- summary ---"
println "subdir '" + refName + "', " + nodes.size() + " tiles"
println result.summary()
def hist = [:].withDefault { 0 }
result.edges().each { hist[it.reject().toString()]++ }
println "rejects: " + hist

int atBound = 0
def tex = [:].withDefault { [] }
def out = new File(new File(folder), "registration_probe_edges.csv")
def pw = new PrintWriter(out)
pw.println("tileA,tileB,reject,ncc,corrDx,corrDy,textureA,textureB,peakRatio,shiftX,shiftY,searchX,searchY,bandW,bandH,atBound")
result.edges().each { e ->
    def d = e.diagnostics()
    if (d.shiftAtSearchBound()) atBound++
    if (!Double.isNaN(d.textureA())) {
        tex[e.reject().toString()] << Math.min(d.textureA(), d.textureB())
    }
    def na = nodes[e.i()].filename()
    def nb = nodes[e.j()].filename()
    pw.println(na + "," + nb + "," + e.reject() + "," + f(e.ncc())
        + "," + f(e.dxPx() - e.nominalDxPx()) + "," + f(e.dyPx() - e.nominalDyPx())
        + "," + f(d.textureA()) + "," + f(d.textureB()) + "," + f(d.secondPeakRatio())
        + "," + f(d.shiftXPx()) + "," + f(d.shiftYPx())
        + "," + d.searchXPx() + "," + d.searchYPx()
        + "," + d.bandWidthPx() + "," + d.bandHeightPx() + "," + d.shiftAtSearchBound())

    if (TILE_A && TILE_B) {
        boolean hit = (na == TILE_A && nb == TILE_B) || (na == TILE_B && nb == TILE_A)
        if (hit) {
            println "--- TARGET EDGE " + na + " " + nb + " ---"
            println "  reject   = " + e.reject() + "   ncc = " + f(e.ncc())
            println "  measured = " + f(e.dxPx() - e.nominalDxPx()) + " , " + f(e.dyPx() - e.nominalDyPx())
            println "  search   = " + d.searchXPx() + " , " + d.searchYPx()
            println "  texture  = " + f(d.textureA()) + " , " + f(d.textureB())
            println "  at bound = " + d.shiftAtSearchBound()
            double[] da = result.deltaFor(na)
            double[] db = result.deltaFor(nb)
            println "  solve applied = " + f(db[0] - da[0]) + " , " + f(db[1] - da[1])
        }
    }
}
pw.close()
println "wrote " + out
println "EDGES AT SEARCH BOUND: " + atBound + " of " + result.edges().size()

println "--- min texture by outcome (min / med / max) ---"
tex.each { k, v ->
    def s = v.sort()
    println k + "  n=" + s.size() + "  " + f(s[0]) + " / " + f(s[(int) (s.size() / 2)]) + " / " + f(s[s.size() - 1])
}
println "(minCoV gate is at " + settings.minCoeffOfVar() + "; compare against the minima above --"
println " if no outcome reaches it, the low-texture gate never fires and is not in play.)"

// ---- per-tile dossier -----------------------------------------------------
// Index every edge by the tiles it touches, so a tile can be looked up directly.
def edgesByTile = [:].withDefault { [] }
result.edges().each { e ->
    edgesByTile[nodes[e.i()].filename()] << e
    edgesByTile[nodes[e.j()].filename()] << e
}
// Column and row indices measured from NOMINAL positions, never inferred from tile numbers.
// Tile numbering is acquisition order and says nothing reliable about layout.
// Cluster rather than match exact doubles: nominal positions come from a micron-to-pixel
// conversion, so two tiles in the same column need not carry bit-identical coordinates, and
// exact keying would shatter the grid into one column per tile.
def indexBy = { values, tol ->
    def sorted = (values as Set).sort()
    def map = [:]
    int idx = -1
    Double prev = null
    sorted.each { v ->
        if (prev == null || (v - prev) > tol) idx++
        map[v] = idx
        prev = v
    }
    return map
}
double tolX = nodes[0].widthPx() / 2.0
double tolY = nodes[0].heightPx() / 2.0
def colOf = indexBy(nodes.collect { it.xPx() }, tolX)
def rowOf = indexBy(nodes.collect { it.yPx() }, tolY)
def nodeByName = [:]
nodes.each { nodeByName[it.filename()] = it }

TILES.each { name ->
    def nd = nodeByName[name]
    if (nd == null) {
        println "--- TILE " + name + ": not present in this subdirectory"
        return
    }
    double[] d = result.deltaFor(name)
    def es = edgesByTile[name]
    int acc = es.count { it.accepted() }
    println "--- TILE " + name + "  col=" + colOf[nd.xPx()] + " row=" + rowOf[nd.yPx()]
    println "    nominal  = " + f(nd.xPx()) + " , " + f(nd.yPx())
    println "    solved   = " + f(d[0]) + " , " + f(d[1]) + "   (correction applied)"
    println "    edges    = " + acc + " accepted of " + es.size()
    if (acc == 0) {
        println "    NO MEASURED EDGE -- this tile's position was diffused from its neighbours."
    }
    es.each { e ->
        def other = (nodes[e.i()].filename() == name) ? nodes[e.j()] : nodes[e.i()]
        def dir = (Math.abs(other.xPx() - nd.xPx()) > Math.abs(other.yPx() - nd.yPx()))
                ? ((other.xPx() > nd.xPx()) ? "RIGHT" : "LEFT")
                : ((other.yPx() > nd.yPx()) ? "BELOW" : "ABOVE")
        def dg = e.diagnostics()
        def line = "      " + dir.padRight(6) + " " + other.filename().padRight(10)
        line = line + " " + e.reject().toString().padRight(12)
        line = line + " ncc=" + f(e.ncc())
        line = line + " measured=" + f(e.dxPx() - e.nominalDxPx())
        line = line + "," + f(e.dyPx() - e.nominalDyPx())
        line = line + " tex=" + f(dg.textureA()) + "/" + f(dg.textureB())
        if (dg.shiftAtSearchBound()) line = line + " AT-BOUND"
        println line
    }
}

// ---- column profile -------------------------------------------------------
// Median solved dx per column. A smooth ramp is a scale error being corrected, which is fine.
// A STEP between two adjacent columns is a seam the whole mosaic is being torn along -- that is
// what a column boundary failing at several different rows looks like from the solve's side.
println "--- median solved dx by column (col: n, medianDx, medianDy) ---"
def byCol = [:].withDefault { [] }
nodes.each { nd ->
    double[] d = result.deltaFor(nd.filename())
    byCol[colOf[nd.xPx()]] << d
}
def prevDx = null
byCol.keySet().sort().each { c ->
    def list = byCol[c]
    def dxs = list.collect { it[0] }.sort()
    def dys = list.collect { it[1] }.sort()
    double mdx = dxs[(int) (dxs.size() / 2)]
    double mdy = dys[(int) (dys.size() / 2)]
    def jump = (prevDx == null) ? "" : ("   step=" + f(mdx - prevDx))
    println "  col " + c + ": n=" + list.size() + "  " + f(mdx) + " , " + f(mdy) + jump
    prevDx = mdx
}
null

static String f(double v) {
    return Double.isNaN(v) ? "-" : String.format(java.util.Locale.ROOT, "%.3f", v)
}
