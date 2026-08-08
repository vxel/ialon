package org.delaunois.ialon.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the land/sea half of the wildlife intensity ({@link AmbientSoundState#shoreFade}).
 *
 * The bug it exists for : the forest-density field the ambience samples is a plain 2D noise that knows
 * nothing about water, so it reads high over the middle of an ocean, and birds were heard out at sea
 * where the generator has never planted a tree. The rule mirrors NoiseTerrainGenerator.treeBandOk
 * ({@code groundh > waterHeight + 1}), softened into a ramp.
 */
class ShoreFadeTest {

    /** The default sea level (IalonConfig.waterHeight). */
    private static final float WATER = 30f;

    @Test
    void fullOnDryLand() {
        assertEquals(1f, AmbientSoundState.shoreFade(WATER + 1f, WATER), 1e-6f, "the tree line itself");
        assertEquals(1f, AmbientSoundState.shoreFade(WATER + 20f, WATER), 1e-6f, "a wooded hillside");
    }

    @Test
    void silentOutAtSea() {
        // Anything deeper than the ramp is open water : no tree stands there, nothing calls.
        assertEquals(0f, AmbientSoundState.shoreFade(WATER - 4f, WATER), 1e-6f, "a few blocks of water");
        assertEquals(0f, AmbientSoundState.shoreFade(WATER - 30f, WATER), 1e-6f, "deep sea");
    }

    @Test
    void theShoreIsARampAndMonotonic() {
        float previous = 0f;
        boolean sawPartial = false;
        for (float ground = WATER - 10f; ground <= WATER + 5f; ground += 0.1f) {
            float v = AmbientSoundState.shoreFade(ground, WATER);
            assertTrue(v >= previous - 1e-6f, "volume dipped while walking UP the beach at " + ground);
            if (v > 0.05f && v < 0.95f) {
                sawPartial = true;
            }
            previous = v;
        }
        assertTrue(sawPartial, "the shore is a cliff, not a ramp : no intermediate level anywhere");
    }

    @Test
    void followsTheWorldSeaLevel() {
        // waterHeight is a per-world setting (IalonConfig / NoiseTerrainGenerator.setWaterHeight), so the
        // fade has to move with it rather than sit on a baked-in 50.
        assertEquals(1f, AmbientSoundState.shoreFade(21f, 20f), 1e-6f);
        assertEquals(0f, AmbientSoundState.shoreFade(21f, 40f), 1e-6f,
                "the same ground is sea floor once the sea is raised over it");
    }
}
