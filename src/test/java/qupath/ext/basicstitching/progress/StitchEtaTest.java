package qupath.ext.basicstitching.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The time-left model on a fake clock. */
class StitchEtaTest {

    private final AtomicLong clock = new AtomicLong();

    private void advance(double seconds) {
        clock.addAndGet((long) (seconds * 1e9));
    }

    /** 1 s per tile, 1 s per band pixel, 1 s per byte: amounts read directly as seconds. */
    private static final StitchEta.Rates UNIT = new StitchEta.Rates(1, 1, 1);

    @Test
    void beforeAnythingRunsTheEstimateIsThePrior() {
        StitchEta eta = new StitchEta(new StitchEta.Work(100, 200, 700), UNIT, clock::get);
        assertEquals(1000, eta.remainingSeconds(), 1e-9);
        assertEquals(1000, StitchEta.predictSeconds(new StitchEta.Work(100, 200, 700), UNIT), 1e-9);
        assertFalse(eta.measured());
    }

    @Test
    void aMeasuredPhaseUsesItsOwnSpeedAndScalesTheLaterPhases() {
        StitchEta eta = new StitchEta(new StitchEta.Work(100, 200, 700), UNIT, clock::get);
        eta.update(StitchPhase.SCAN, 0);
        // The scan runs twice as slowly as its prior: 10 % per 20 s instead of per 10 s.
        for (int i = 1; i <= 4; i++) {
            advance(20);
            eta.update(StitchPhase.SCAN, 0.1 * i);
        }
        assertTrue(eta.measured());
        assertEquals(2.0, eta.slowdown(), 1e-9);
        // Scan: 60 % left at the measured 200 s per whole scan = 120 s; solve and write: 900 x 2.
        assertEquals(120 + 1800, eta.remainingSeconds(), 1e-6);
    }

    @Test
    void beforeWarmupThePriorTimesTheSlowdownSoFarApplies() {
        StitchEta eta = new StitchEta(new StitchEta.Work(0, 0, 1000), UNIT, clock::get);
        eta.update(StitchPhase.WRITE, 0);
        advance(10);
        eta.update(StitchPhase.WRITE, 0.5); // fast, but only 10 s: not trusted yet
        assertFalse(eta.measured());
        assertEquals(500, eta.remainingSeconds(), 1e-9);
    }

    @Test
    void theWindowFollowsAChangeOfSpeed() {
        StitchEta eta = new StitchEta(new StitchEta.Work(0, 0, 100_000), UNIT, clock::get);
        eta.update(StitchPhase.WRITE, 0);
        double f = 0;
        // 20 min at 1 %/min, then 20 min at 0.5 %/min (another stitch started alongside).
        for (int i = 0; i < 20; i++) {
            advance(60);
            f += 0.01;
            eta.update(StitchPhase.WRITE, f);
        }
        for (int i = 0; i < 20; i++) {
            advance(60);
            f += 0.005;
            eta.update(StitchPhase.WRITE, f);
        }
        // The last 10 min ran at 0.5 %/min: 70 % left -> 140 min.
        assertEquals(140 * 60, eta.remainingSeconds(), 1.0);
    }

    @Test
    void aStallDoesNotCountDownToZero() {
        StitchEta eta = new StitchEta(new StitchEta.Work(0, 0, 100_000), UNIT, clock::get);
        eta.update(StitchPhase.WRITE, 0);
        for (int i = 1; i <= 5; i++) {
            advance(60);
            eta.update(StitchPhase.WRITE, 0.01 * i);
        }
        double before = eta.remainingSeconds();
        advance(3600);
        assertEquals(before, eta.remainingSeconds(), 1e-9);
    }

    @Test
    void finishedPhasesRecordTheirMeasuredRate() {
        StitchEta eta = new StitchEta(new StitchEta.Work(100, 0, 1000), UNIT, clock::get);
        eta.update(StitchPhase.SCAN, 0);
        advance(50);
        eta.update(StitchPhase.SCAN, 1);
        eta.update(StitchPhase.WRITE, 0);
        advance(40);
        eta.update(StitchPhase.WRITE, 1);
        eta.finish();
        assertEquals(0.5, eta.measuredRate(StitchPhase.SCAN), 1e-9);
        assertEquals(0.04, eta.measuredRate(StitchPhase.WRITE), 1e-9);
        assertTrue(Double.isNaN(eta.measuredRate(StitchPhase.SOLVE)));
    }

    @Test
    void theRigLogIsReproducedByTheDefaultRates() {
        // 2026-11-06 PPM: 2,381 tiles scanned in 47 s; 90.0 level 1 (111932 x 97619 RGB, J2K) in
        // 5,448 s. Defaults within 10 % of both.
        StitchEta.Rates j2k = StitchEta.Rates.defaults("J2K");
        assertEquals(47, 2381 * j2k.scanSecondsPerTile(), 4.7);
        assertEquals(5448, 111932L * 97619L * 3 * j2k.writeSecondsPerByte(), 545);
    }

    @Test
    void durationsReadNaturally() {
        assertEquals("45 s", StitchProgress.formatDuration(45));
        assertEquals("12 min", StitchProgress.formatDuration(12 * 60 + 10));
        assertEquals("3 h 05 min", StitchProgress.formatDuration(3 * 3600 + 5 * 60));
        assertEquals("time left unknown", StitchProgress.formatRemaining(Double.NaN));
        assertEquals("about 2 h 00 min left", StitchProgress.formatRemaining(7200));
    }
}
