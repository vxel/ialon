package org.delaunois.ialon.support;

import com.jme3.math.Vector3f;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.NoiseTerrainGenerator;

/**
 * Manual (interactive) analysis tool for the terrain height distribution — NOT part of the
 * automated suite. Run its {@link #main(String[])} from the IDE.
 *
 * <p>Samples the EXACT game noise over a large square area centred on the origin and reports the
 * distribution of the ground height (min / mean / max, a 10-block histogram) plus how often the
 * terrain reaches the world ceiling. It is the answer to "with this {@code gridHeight}, can a
 * mountain ever poke through the top of the world ?".
 *
 * <p>It builds the same {@link NoiseTerrainGenerator} the game uses (seed {@value #SEED}, the
 * config's water height) and calls its {@code getHeight(...)}, so it always reflects the CURRENT
 * tuning in {@code NoiseTerrainGenerator.createWorldNoise()} and the {@link IalonConfig} grid
 * dimensions — no parameters are duplicated here. No jME context is needed (the height sampling
 * touches only the noise), so it runs as a plain {@code main()}.
 *
 * <p>Optional args : {@code [seed] [span] [step]} (defaults {@value #SEED} / {@value #SPAN} /
 * {@value #STEP}).
 */
public class NoiseHeightAnalysis {

    /** Default world seed used by {@code IalonConfig.getDefaultChunkGenerator()}. */
    private static final long SEED = 2;

    /** World units sampled on each axis, centred on the origin. */
    private static final int SPAN = 6000;

    /** Sampling step (in world units) along each axis. */
    private static final int STEP = 2;

    /**
     * Number of blocks an oak tree's canopy reaches ABOVE the ground block it grows on :
     * TRUNK_HEIGHT(3) + 2*CANOPY_RADIUS(3) - 1 = 8 (see NoiseTerrainGenerator.createCanopy). Trees
     * only grow where the ground is above {@code waterHeight + 1}.
     */
    private static final int TREE_CANOPY_TOP = 8;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : SEED;
        int span = args.length > 1 ? Integer.parseInt(args[1]) : SPAN;
        int step = args.length > 2 ? Integer.parseInt(args[2]) : STEP;

        IalonConfig config = new IalonConfig();
        float waterHeight = config.getWaterHeight();
        int ceiling = config.getMaxy(); // gridHeight * chunkHeight : the top of the world (exclusive)

        NoiseTerrainGenerator generator = new NoiseTerrainGenerator(seed, waterHeight);

        analyse(generator, seed, span, step, waterHeight, ceiling);
    }

    private static void analyse(NoiseTerrainGenerator generator, long seed, int span, int step,
                                float waterHeight, int ceiling) {
        Vector3f location = new Vector3f();

        float maxGround = -Float.MAX_VALUE;
        float minGround = Float.MAX_VALUE;
        double sum = 0;
        long n = 0;
        long overCeiling = 0;          // ground alone reaches the ceiling
        long overCeilingWithTree = 0;  // ground + canopy reaches the ceiling (only where trees grow)
        int[] histogram = new int[32]; // 10-block buckets : 0..320

        int half = span / 2;
        for (int x = -half; x <= half; x += step) {
            for (int z = -half; z <= half; z += step) {
                float h = generator.getHeight(location.set(x, 0, z));
                int g = (int) h;

                if (h > maxGround) maxGround = h;
                if (h < minGround) minGround = h;
                sum += h;
                n++;

                int bucket = Math.min(histogram.length - 1, Math.max(0, g / 10));
                histogram[bucket]++;

                if (g >= ceiling) overCeiling++;
                if (h > waterHeight + 1 && g + TREE_CANOPY_TOP >= ceiling) overCeilingWithTree++;
            }
        }

        report(seed, span, step, waterHeight, ceiling, n, minGround, sum / n, maxGround,
                overCeiling, overCeilingWithTree, histogram);
    }

    private static void report(long seed, int span, int step, float waterHeight, int ceiling, long n,
                               float min, double mean, float max, long overCeiling,
                               long overCeilingWithTree, int[] histogram) {
        System.out.println("=== Terrain height analysis ===");
        System.out.printf("seed=%d  water height=%.0f  ceiling (gridHeight*chunkHeight)=%d%n",
                seed, waterHeight, ceiling);
        System.out.printf("samples            : %d  (%dx%d area, step %d)%n", n, span, span, step);
        System.out.printf("ground height min  : %.2f%n", min);
        System.out.printf("ground height mean : %.2f%n", mean);
        System.out.printf("ground height max  : %.2f%n", max);
        System.out.printf("headroom under ceiling : %.2f%n", ceiling - max);
        System.out.printf("columns ground >= ceiling            : %d (%.6f%%)%n",
                overCeiling, 100.0 * overCeiling / n);
        System.out.printf("columns ground + tree(%d) >= ceiling : %d (%.6f%%)%n",
                TREE_CANOPY_TOP, overCeilingWithTree, 100.0 * overCeilingWithTree / n);
        System.out.println("ground height histogram (bucket = 10 blocks):");
        for (int i = 0; i < histogram.length; i++) {
            if (histogram[i] > 0) {
                System.out.printf("  [%3d-%3d) : %d%n", i * 10, i * 10 + 10, histogram[i]);
            }
        }
    }
}
