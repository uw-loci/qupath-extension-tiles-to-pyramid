package qupath.ext.basicstitching;

/**
 * Heap measurement for the tests that assert the streaming design's bounded footprint.
 *
 * <p>Shared so there is one place explaining why the collection is forced, and one entry in the
 * SpotBugs exclusion filter rather than one per test class.
 */
final class HeapMeasurement {

    private HeapMeasurement() {}

    /**
     * Live heap after asking for a collection, repeated until the reading stops falling.
     *
     * <p>{@code System.gc()} is a hint, so a single call can return before anything has been
     * reclaimed. Iterating until two consecutive readings agree makes the measurement about what is
     * still reachable rather than about when the collector happened to run -- which matters because
     * these tests share a JVM with every other test, and an uncollected pile of someone else's
     * garbage otherwise reads as this code's retention.
     *
     * @param runtime the runtime to measure
     * @return bytes in use once the reading settles
     */
    static long settledHeapBytes(Runtime runtime) {
        long previous = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            System.gc();
            long used = runtime.totalMemory() - runtime.freeMemory();
            if (used >= previous) {
                return used;
            }
            previous = used;
        }
        return previous;
    }
}
