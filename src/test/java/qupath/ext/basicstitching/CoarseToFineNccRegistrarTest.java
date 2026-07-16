package qupath.ext.basicstitching;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.registration.CoarseToFineNccRegistrar;
import qupath.ext.basicstitching.registration.EdgeMeasurement;
import qupath.ext.basicstitching.registration.Ncc;
import qupath.ext.basicstitching.registration.NeighborGraphBuilder.EdgePair;
import qupath.ext.basicstitching.registration.OverlapBand;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.RejectReason;

/**
 * Tests for the pairwise correlation search and its gates.
 *
 * <p>The rejection tests carry most of the weight. The guiding rule for this whole subsystem is
 * that a correct nominal position beats a confident wrong correction: a missed match costs nothing
 * (the tile stays where the stage said), whereas a believed-but-wrong match throws a tile across
 * the mosaic. Every gate below is a specific way our data is known to produce confident nonsense.
 */
class CoarseToFineNccRegistrarTest {

    private static final int BAND_W = 140;
    private static final int BAND_H = 200;
    private static final int MARGIN = 40;

    private final CoarseToFineNccRegistrar registrar = new CoarseToFineNccRegistrar();
    private final EdgePair edge = new EdgePair(0, 1, true);

    // ---------------------------------------------------------------- helpers

    /**
     * Band-limited noise: random values at several octaves, bilinearly upsampled and summed.
     *
     * <p>Flat white noise would be the wrong fixture -- it vanishes under the pyramid's box filter,
     * so the coarse level would have nothing to lock onto and the test would be measuring the
     * refinement stage only. Multi-octave noise has real structure at every level, which is what a
     * tissue image looks like to this search.
     */
    private static float[][] texture(int w, int h, long seed) {
        Random rng = new Random(seed);
        double[][] acc = new double[h][w];
        for (int octave = 0; octave < 5; octave++) {
            int cell = 1 << (octave + 2);
            int gw = w / cell + 2;
            int gh = h / cell + 2;
            double[][] grid = new double[gh][gw];
            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    grid[y][x] = rng.nextDouble();
                }
            }
            double amp = 1.0 / (octave + 1);
            for (int y = 0; y < h; y++) {
                double gy = (double) y / cell;
                int y0 = (int) gy;
                double fy = gy - y0;
                for (int x = 0; x < w; x++) {
                    double gx = (double) x / cell;
                    int x0 = (int) gx;
                    double fx = gx - x0;
                    double top = grid[y0][x0] * (1 - fx) + grid[y0][x0 + 1] * fx;
                    double bot = grid[y0 + 1][x0] * (1 - fx) + grid[y0 + 1][x0 + 1] * fx;
                    acc[y][x] += amp * (top * (1 - fy) + bot * fy);
                }
            }
        }
        float[][] out = new float[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y][x] = (float) (1000 + 8000 * acc[y][x]);
            }
        }
        return out;
    }

    private static OverlapBand band(float[][] gray) {
        int n = gray.length * gray[0].length;
        double[] flat = new double[n];
        double sum = 0;
        double sumSq = 0;
        int i = 0;
        for (float[] row : gray) {
            for (float v : row) {
                flat[i++] = v;
                sum += v;
                sumSq += (double) v * v;
            }
        }
        double mean = sum / n;
        return new OverlapBand(
                gray, Ncc.medianOf(flat), Ncc.robustSpread(flat), Math.max(0, sumSq / n - mean * mean), 65535);
    }

    /** Crop a band out of a source at the given offset. */
    private static float[][] crop(float[][] src, int x0, int y0, int w, int h) {
        float[][] out = new float[h][w];
        for (int y = 0; y < h; y++) {
            System.arraycopy(src[y0 + y], x0, out[y], 0, w);
        }
        return out;
    }

    /**
     * Two bands of the same scene where B is displaced from A by {@code (ex, ey)}.
     *
     * <p>A correct measurement returns exactly {@code (ex, ey)} as the correction to nominal.
     */
    private EdgeMeasurement measureShift(float[][] src, int ex, int ey, RegistrationSettings settings) {
        OverlapBand a = band(crop(src, MARGIN, MARGIN, BAND_W, BAND_H));
        OverlapBand b = band(crop(src, MARGIN + ex, MARGIN + ey, BAND_W, BAND_H));
        return registrar.measure(edge, a, b, 1260, 0, 30, 30, settings);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void recoversKnownShift_texturedBand() {
        float[][] src = texture(BAND_W + 2 * MARGIN, BAND_H + 2 * MARGIN, 42);
        EdgeMeasurement m = measureShift(src, 7, -5, RegistrationSettings.defaults());

        assertEquals(RejectReason.NONE, m.reject(), "textured bands with a real shift must register");
        assertEquals(7, m.deltaFromNominalXPx(), 1.0);
        assertEquals(-5, m.deltaFromNominalYPx(), 1.0);
        assertEquals(1260 + 7, m.dxPx(), 1.0, "measured offset is nominal plus the correction");
        assertTrue(m.ncc() > 0.9, "an exact shift of the same content should correlate near 1, got " + m.ncc());
    }

    @Test
    void recoversZeroShift_whenNominalIsAlreadyCorrect() {
        float[][] src = texture(BAND_W + 2 * MARGIN, BAND_H + 2 * MARGIN, 7);
        EdgeMeasurement m = measureShift(src, 0, 0, RegistrationSettings.defaults());

        assertEquals(RejectReason.NONE, m.reject());
        assertEquals(0, m.deltaFromNominalXPx(), 1.0);
        assertEquals(0, m.deltaFromNominalYPx(), 1.0);
    }

    @Test
    void rejectsUniformBand() {
        float[][] flat = new float[BAND_H][BAND_W];
        for (float[] row : flat) {
            java.util.Arrays.fill(row, 500f);
        }
        EdgeMeasurement m =
                registrar.measure(edge, band(flat), band(flat), 1260, 0, 30, 30, RegistrationSettings.defaults());
        assertEquals(RejectReason.LOW_VARIANCE, m.reject());
    }

    @Test
    void rejectsBrightUniformBand_ppmBackground() {
        // The realistic failure: a bright, low-contrast field. Its absolute variance is large
        // (hundreds), so an absolute-variance gate would wave it through; its coefficient of
        // variation is tiny, which is what actually characterises "no structure here".
        Random rng = new Random(1);
        float[][] bright = new float[BAND_H][BAND_W];
        for (float[] row : bright) {
            for (int x = 0; x < BAND_W; x++) {
                row[x] = (float) (60000 + rng.nextGaussian() * 30);
            }
        }
        OverlapBand b = band(bright);
        assertTrue(b.variance() > 100, "fixture must have large ABSOLUTE variance to be a fair test");
        assertTrue(b.textureScore() < 0.02, "but a tiny texture score");

        EdgeMeasurement m = registrar.measure(edge, b, b, 1260, 0, 30, 30, RegistrationSettings.defaults());
        assertEquals(RejectReason.LOW_VARIANCE, m.reject(), "bright low-contrast background must not register");
    }

    @Test
    void rejectsSaturatedBand() {
        float[][] blown = new float[BAND_H][BAND_W];
        for (float[] row : blown) {
            java.util.Arrays.fill(row, 65535f);
        }
        EdgeMeasurement m =
                registrar.measure(edge, band(blown), band(blown), 1260, 0, 30, 30, RegistrationSettings.defaults());
        assertEquals(RejectReason.LOW_VARIANCE, m.reject());
    }

    @Test
    void rejectsPeriodicTexture() {
        // A regular grating correlates equally well at every period, so no shift is defensible.
        int w = BAND_W + 2 * MARGIN;
        int h = BAND_H + 2 * MARGIN;
        float[][] grating = new float[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                grating[y][x] = (float) (5000 + 3000 * Math.sin(2 * Math.PI * x / 16.0));
            }
        }
        EdgeMeasurement m = measureShift(grating, 3, 0, RegistrationSettings.defaults());
        assertEquals(RejectReason.AMBIGUOUS, m.reject(), "repeating texture must be refused, not guessed at");
    }

    @Test
    void rejectsBoundaryPeak_neverSilentlyClamps() {
        // True shift lies outside the search window. The peak pins to the boundary, which means the
        // real answer is out of physical bounds -- the measurement must be thrown away rather than
        // clamped into range, which would fabricate a plausible-looking correction.
        float[][] src = texture(BAND_W + 2 * MARGIN, BAND_H + 2 * MARGIN, 11);
        OverlapBand a = band(crop(src, MARGIN, MARGIN, BAND_W, BAND_H));
        OverlapBand b = band(crop(src, MARGIN + 12, MARGIN, BAND_W, BAND_H));

        EdgeMeasurement m = registrar.measure(edge, a, b, 1260, 0, 8, 8, RegistrationSettings.defaults());

        assertEquals(RejectReason.OUT_OF_BAND, m.reject());
        assertFalse(m.accepted());
    }

    @Test
    void singleBrightSpeck_doesNotDominate() {
        // Dust guard: one hot pixel on an otherwise featureless field is enough to produce a sharp,
        // confident correlation peak that means nothing about where the tile belongs.
        float[][] a = new float[BAND_H][BAND_W];
        float[][] b = new float[BAND_H][BAND_W];
        for (int y = 0; y < BAND_H; y++) {
            java.util.Arrays.fill(a[y], 500f);
            java.util.Arrays.fill(b[y], 500f);
        }
        a[100][70] = 60000f;
        b[95][63] = 60000f;

        EdgeMeasurement m = registrar.measure(edge, band(a), band(b), 1260, 0, 30, 30, RegistrationSettings.defaults());
        assertNotEquals(RejectReason.NONE, m.reject(), "a lone speck on a flat field must not be believed");
    }

    @Test
    void tooSmallBandIsNoOverlap() {
        float[][] tiny = new float[8][8];
        EdgeMeasurement m =
                registrar.measure(edge, band(tiny), band(tiny), 1260, 0, 4, 4, RegistrationSettings.defaults());
        assertEquals(RejectReason.NO_OVERLAP, m.reject());
    }

    @Test
    void rejectedEdgeReportsNominalOffsetUnchanged() {
        // A rejected edge must not smuggle a correction through its dx/dy fields.
        float[][] flat = new float[BAND_H][BAND_W];
        for (float[] row : flat) {
            java.util.Arrays.fill(row, 500f);
        }
        EdgeMeasurement m =
                registrar.measure(edge, band(flat), band(flat), 1260, 33, 30, 30, RegistrationSettings.defaults());
        assertEquals(0, m.deltaFromNominalXPx(), 1e-9);
        assertEquals(0, m.deltaFromNominalYPx(), 1e-9);
        assertEquals(0, m.weight(0.3), 1e-9, "rejected edges must carry zero weight into the solve");
    }
}
