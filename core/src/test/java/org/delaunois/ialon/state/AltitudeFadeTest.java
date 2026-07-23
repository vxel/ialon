package org.delaunois.ialon.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the altitude band the wildlife is heard in ({@link AmbientSoundState#altitudeFade}).
 *
 * The thresholds are not taste, they come from geometry measured on the terrain, and a change that
 * looks harmless breaks one of the two ends : lower the top of the band and the volume dips every time
 * the player jumps ; raise the bottom and the birds follow you into a cave.
 */
class AltitudeFadeTest {

    /**
     * Worst case measured over 437 land columns : the top of the ground block sits within [-0.92, 1.00]
     * of the noise height, and the eye is 1.45 above the block it stands on.
     */
    private static final float STANDING_EYE = 2.45f;
    /** Jump apex on top of that : v^2 / 2g with jumpSpeed 5 and groundGravity 9. */
    private static final float JUMP_APEX = STANDING_EYE + (5f * 5f) / (2f * 9f);

    @Test
    void fullVolumeWhereThePlayerActuallyWalks() {
        assertEquals(1f, AmbientSoundState.altitudeFade(STANDING_EYE), 1e-6f, "standing on the surface");
        assertEquals(1f, AmbientSoundState.altitudeFade(JUMP_APEX), 1e-6f,
                "jumping must not duck the ambience — " + JUMP_APEX + " blocks up");
        assertEquals(1f, AmbientSoundState.altitudeFade(0f), 1e-6f);
    }

    @Test
    void silentWhenFlying() {
        // Where exactly the band closes is a tuning choice and is deliberately NOT pinned here — an
        // earlier version asserted "under 10% by 10 blocks" and simply broke when the band was widened,
        // which is the test getting in the way rather than protecting anything. What must hold at any
        // setting is that flying really does end in silence, and reasonably soon.
        assertEquals(0f, AmbientSoundState.altitudeFade(50f), 1e-6f, "cruising altitude");
        assertEquals(0f, AmbientSoundState.altitudeFade(500f), 1e-6f);
        assertTrue(AmbientSoundState.altitudeFade(30f) < 0.01f,
                "30 blocks up is unambiguously flying, got " + AmbientSoundState.altitudeFade(30f));
    }

    @Test
    void theClimbOutIsGradualAndMonotonic() {
        // "Diminuer au fur et à mesure" : no step, no bounce, and a real ramp rather than a switch.
        float previous = 1f;
        boolean sawPartial = false;
        for (float h = JUMP_APEX; h <= 40f; h += 0.1f) {
            float v = AmbientSoundState.altitudeFade(h);
            assertTrue(v <= previous + 1e-6f, "volume went back UP at " + h + " blocks");
            if (v > 0.05f && v < 0.95f) {
                sawPartial = true;
            }
            previous = v;
        }
        assertTrue(sawPartial, "the fade is a cliff, not a ramp : no intermediate volume anywhere");
    }

    @Test
    void stillSilentUnderground() {
        // The other end of the band, which the fly fade must not have disturbed.
        assertEquals(0f, AmbientSoundState.altitudeFade(-8f), 1e-6f, "deep in a cave");
        // Dead centre of the downward ramp (-2 to -8), so exactly half by construction.
        assertEquals(0.5f, AmbientSoundState.altitudeFade(-5f), 1e-6f, "part way down a shaft");
        assertEquals(1f, AmbientSoundState.altitudeFade(-2f), 1e-6f, "just below the surface line");
    }
}
