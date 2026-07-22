package qupath.ext.basicstitching;

/**
 * Test-only probe for whether the native blosc library can actually be loaded in this JVM.
 *
 * <h2>Why this exists</h2>
 *
 * OME-ZARR stitching compresses through jblosc, which loads a platform-specific native
 * ({@code blosc.dll} / {@code libblosc.so} / {@code libblosc.dylib}). In production that native is
 * supplied by the QuPath application the extension runs inside -- this extension deliberately does
 * NOT bundle it (bundling shipped a Linux native to every platform; see the 0.6.0 dependency fix).
 *
 * <p>A Gradle test JVM has no QuPath install behind it, so it resolves the blosc jar for the
 * <i>build machine's</i> platform via ordinary dependency resolution. On the Linux CI runner and on
 * developer machines that happens to be the right one and ZARR tests run. On the Windows CI runner
 * it resolves the Linux native, and every ZARR test dies with an {@code UnsatisfiedLinkError} that
 * says nothing about the code under test.
 *
 * <h2>Why a probe, not {@code @DisabledOnOs(WINDOWS)}</h2>
 *
 * The failure is not "Windows" -- it is "no loadable blosc on the test classpath", which is an
 * environment fact, not a platform one. Probing the actual condition means:
 * <ul>
 *   <li>the tests run wherever blosc genuinely loads, including a correctly-provisioned Windows box;</li>
 *   <li>they self-heal if the CI environment ever gains a working native, with no code change;</li>
 *   <li>a skip reports the real reason, so a future reader is not misled into thinking ZARR is
 *       untested on Windows by policy when it is untested by classpath.</li>
 * </ul>
 *
 * <p>This never masks a real regression: the probe forces exactly the same class initialization the
 * ZARR path would, so if blosc is loadable the tests run in full, and if a real code change breaks
 * blosc loading the probe reports it as a skip reason rather than hiding it.
 */
final class BloscSupport {

    private static final boolean AVAILABLE;
    private static final String UNAVAILABLE_REASON;

    static {
        boolean available = false;
        String reason = null;
        try {
            // Force the SAME JNA native registration the ZARR compressor path triggers. The native
            // is registered in org.blosc.IBloscDll's static initializer (via Native.register), which
            // JBlosc only reaches lazily on the first compress/decompress call -- its constructor
            // does NOT, so `new JBlosc()` loaded nothing and this probe reported a false positive on
            // a platform whose native is missing (the Windows CI runner, which resolves the Linux
            // native). Initialize IBloscDll directly (by internal class name, deliberately) so the
            // probe fails at exactly the point the real ZARR write would.
            Class.forName("org.blosc.IBloscDll");
            available = true;
        } catch (Throwable t) {
            // A failed class-init surfaces as ExceptionInInitializerError (first touch) wrapping the
            // real UnsatisfiedLinkError; unwrap it so the skip reason names the actual cause.
            Throwable cause = t instanceof ExceptionInInitializerError && t.getCause() != null ? t.getCause() : t;
            reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        AVAILABLE = available;
        UNAVAILABLE_REASON = reason;
    }

    private BloscSupport() {}

    /** @return whether the blosc native can be loaded in this JVM. */
    static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * @return a human-readable reason blosc could not be loaded, for use as a skip message; a
     *     generic note if it is in fact available
     */
    static String unavailableReason() {
        return AVAILABLE
                ? "blosc is available"
                : "native blosc library not loadable on this test classpath (provided by the QuPath "
                        + "app in production, absent from a bare Gradle test JVM): " + UNAVAILABLE_REASON;
    }
}
