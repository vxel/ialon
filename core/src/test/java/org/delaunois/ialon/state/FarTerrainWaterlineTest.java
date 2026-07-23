package org.delaunois.ialon.state;

import org.delaunois.ialon.IalonConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the z-fighting seen along the DISTANT coastlines, between the far relief mesh and
 * the flat far water plane.
 * <p>
 * The relief is the true, continuous generator height, so around every shore it crosses the water plane
 * at a very grazing angle : the band where the two surfaces are less than a unit apart spans hundreds of
 * world units on the ground. No depth buffer resolves that at horizon distances — the resolvable step
 * grows as {@code z^2 / (near * 2^bits)}, so at ~1000 units it is already comparable to the gap — and the
 * whole band stipples.
 * <p>
 * {@code FarTerrainState.clampAcrossWaterline} fixes that by keeping every relief sample a minimum
 * clearance away from the plane. The two properties the fix relies on, asserted here :
 * <ul>
 *   <li><b>clearance</b> — no sample may end up within the clearance band, so the surfaces are never
 *       near-coplanar ; and</li>
 *   <li><b>side preservation</b> — a sample never crosses the water line, so the coastline CONTOUR is
 *       unchanged (only its vertical profile becomes a step). Without this the fix would silently
 *       redraw the map's shores.</li>
 * </ul>
 */
class FarTerrainWaterlineTest {

    @Test
    void keepsEverySampleClearOfTheWaterPlaneWithoutMovingTheShore() {
        IalonConfig config = new IalonConfig();
        FarTerrainState state = new FarTerrainState(config);

        float waterline = state.waterlineHeight();
        float clearance = config.getFarTerrainWaterlineClearance();
        assertTrue(clearance > 0f, "the default config must enable the clearance");

        // The water plane sits at waterHeight + 0.5 + Liquid.topOffset(LIQUID_LEVEL5) = 30.8, and the
        // relief mesh is translated up by farTerrainVerticalOffset, so the relief crosses the plane at
        // 30.8 - 1.0. Pinned : the whole fix is expressed relative to this value.
        assertEquals(29.8f, waterline, 1e-4f);

        // Sweep the shore neighbourhood far more finely than the 16-unit heightmap step ever samples it.
        for (int i = -2000; i <= 2000; i++) {
            float h = waterline + i * 0.001f;
            float clamped = state.clampAcrossWaterline(h);

            assertTrue(Math.abs(clamped - waterline) >= clearance - 1e-4f,
                    "height " + h + " was left " + clamped + ", inside the clearance band around " + waterline);

            // Side preservation. The h == waterline case belongs to the land branch by convention.
            if (h >= waterline) {
                assertTrue(clamped > waterline, "land sample " + h + " must stay land, got " + clamped);
            } else {
                assertTrue(clamped < waterline, "sea sample " + h + " must stay sea, got " + clamped);
            }
        }
    }

    @Test
    void leavesHeightsWellClearOfTheWaterlineUntouched() {
        IalonConfig config = new IalonConfig();
        FarTerrainState state = new FarTerrainState(config);
        float waterline = state.waterlineHeight();
        float clearance = config.getFarTerrainWaterlineClearance();

        // Mountains and deep sea floor must be reproduced exactly : the clamp is a shoreline-only fix.
        for (float h : new float[]{0f, 5f, waterline - clearance - 0.5f, waterline + clearance + 0.5f, 80f, 200f}) {
            assertEquals(h, state.clampAcrossWaterline(h), 1e-6f,
                    "height " + h + " is outside the clearance band and must be untouched");
        }
    }

    @Test
    void clearanceZeroDisablesTheClamp() {
        IalonConfig config = new IalonConfig();
        config.setFarTerrainWaterlineClearance(0f);
        FarTerrainState state = new FarTerrainState(config);
        float waterline = state.waterlineHeight();

        assertEquals(waterline, state.clampAcrossWaterline(waterline), 1e-6f);
        assertEquals(waterline + 0.01f, state.clampAcrossWaterline(waterline + 0.01f), 1e-6f);
    }
}
