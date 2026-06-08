package org.delaunois.ialon;

import com.jme3.math.Vector3f;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;

/**
 * Validates that the finite (torus) world has perfect edge joins : with a tiling period W, the
 * terrain height is periodic in X and Z, so {@code getHeight(p) == getHeight(p + W)} on both axes.
 * This is what makes the -x/+x and -z/+z borders match seamlessly when the player wraps around.
 */
class WorldTilingTest {

    private static final float W = 4096f;       // tiling period (world units)
    private static final int WORLD_HEIGHT = 7 * 16;
    private static final float WATER_HEIGHT = 30f;

    @Test
    void heightIsPeriodicInXAndZ() {
        NoiseTerrainGenerator gen = new NoiseTerrainGenerator(2, WATER_HEIGHT, WORLD_HEIGHT, W);

        // A spread of sample points, including the seam (±W/2) and off-grid fractional positions.
        float[] xs = {0f, 1f, 37.5f, -123.25f, W / 2f, -W / 2f, 2000f};
        float[] zs = {0f, -1f, 64.75f, 512f, W / 2f - 0.5f, -W / 2f + 0.5f, -777f};

        for (float x : xs) {
            for (float z : zs) {
                float h = gen.getHeight(new Vector3f(x, 0f, z));
                // Periodic in X.
                assertEquals(h, gen.getHeight(new Vector3f(x + W, 0f, z)), 1e-3f,
                        "height must repeat after +W in X at (" + x + ", " + z + ")");
                // Periodic in Z.
                assertEquals(h, gen.getHeight(new Vector3f(x, 0f, z + W)), 1e-3f,
                        "height must repeat after +W in Z at (" + x + ", " + z + ")");
                // Periodic across the seam itself (the -x edge equals the +x edge, shifted by W).
                assertEquals(h, gen.getHeight(new Vector3f(x - W, 0f, z - W)), 1e-3f,
                        "height must repeat after -W on both axes at (" + x + ", " + z + ")");
            }
        }
    }

    @Test
    void wrapToWorldLandsNearOriginOnIdenticalTerrain() {
        IalonConfig config = new IalonConfig(); // finiteWorld + worldSizeChunks = 256 by default
        float w = config.getWorldSize();
        assertEquals(W, w, 0f, "default config world size should be 4096");

        NoiseTerrainGenerator gen = new NoiseTerrainGenerator(2, WATER_HEIGHT, WORLD_HEIGHT, w);

        // A position several tiles away from the origin (as it would be after a long walk).
        Vector3f far = new Vector3f(3f * w + 137.5f, 12f, -2f * w - 88.25f);
        float hFar = gen.getHeight(far);

        Vector3f wrapped = config.wrapToWorld(new Vector3f(far));
        // Brought back into the tile centered on the origin.
        assertTrue(Math.abs(wrapped.x) <= w / 2f, "wrapped X must be within [-W/2, W/2]");
        assertTrue(Math.abs(wrapped.z) <= w / 2f, "wrapped Z must be within [-W/2, W/2]");
        assertEquals(12f, wrapped.y, 0f, "Y must be left untouched (not circular)");
        // ... and onto exactly the same ground (no visible jump).
        assertEquals(hFar, gen.getHeight(wrapped), 1e-3f, "wrapped position must sit on identical terrain");
    }

    @Test
    void tilingPeriodChangesTheRelief() {
        // Sanity check : tiled and non-tiled generators are genuinely different fields (the tiling is
        // doing something), so the periodicity above is not a degenerate flat/constant result.
        NoiseTerrainGenerator tiled = new NoiseTerrainGenerator(2, WATER_HEIGHT, WORLD_HEIGHT, W);
        NoiseTerrainGenerator infinite = new NoiseTerrainGenerator(2, WATER_HEIGHT, WORLD_HEIGHT, 0f);
        assertNotEquals(
                infinite.getHeight(new Vector3f(1000f, 0f, 1000f)),
                tiled.getHeight(new Vector3f(1000f, 0f, 1000f)),
                "tiled relief is expected to differ from the infinite relief");
    }
}
