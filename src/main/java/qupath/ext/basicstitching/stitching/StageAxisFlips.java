package qupath.ext.basicstitching.stitching;

/**
 * Which stage-axis negations a run actually used.
 *
 * <p>The two strategies that read stage coordinates keep their flip flags in separate statics, and
 * a caller may set only the pair belonging to the method it is about to run. Anything that reports
 * or records the flips therefore has to pick the right pair, and picking the wrong one is silent:
 * the stitch is correct, only the record of it is wrong.
 *
 * <p>That is not cosmetic, because the registration solution's header carries the flips and
 * {@code TileRegistrationSolution.incompatibilityReason} rejects a cached solution whose flips
 * disagree with the run. A header written from the other strategy's statics can therefore both
 * discard a solution that was fine and accept one that was solved for a mirrored layout.
 */
public record StageAxisFlips(boolean x, boolean y) {

    /**
     * @param stitchingType the method identifier {@link StitchingStrategyFactory} switches on
     * @return the flip flags belonging to the strategy that identifier selects
     */
    public static StageAxisFlips forMethod(String stitchingType) {
        return isMicroManager(stitchingType)
                ? new StageAxisFlips(
                        MicroManagerMetadataStrategy.flipStitchingX, MicroManagerMetadataStrategy.flipStitchingY)
                : new StageAxisFlips(
                        TileConfigurationTxtStrategy.flipStitchingX, TileConfigurationTxtStrategy.flipStitchingY);
    }

    /** True for the MicroManager method, whose flips live on {@link MicroManagerMetadataStrategy}. */
    public static boolean isMicroManager(String stitchingType) {
        return stitchingType != null && stitchingType.startsWith("MicroManager");
    }
}
