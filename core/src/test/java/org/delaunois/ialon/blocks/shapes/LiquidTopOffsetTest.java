package org.delaunois.ialon.blocks.shapes;

import org.delaunois.ialon.blocks.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the crash seen when digging deep underground :
 * {@code ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 7} in
 * {@code Liquid.topOffset}, reached from {@code UnderwaterState.update}.
 * <p>
 * The camera regularly ends up inside a NON-liquid block (it clips into the wall or floor of a freshly
 * dug tunnel), and every non-liquid block carries {@link Block#LIQUID_DISABLED} (-1) as its liquid level.
 * {@code topOffset} clamped only the upper bound, so that -1 indexed the heights array out of bounds.
 * Levels are now clamped on both ends.
 */
class LiquidTopOffsetTest {

    @Test
    void clampsANonLiquidLevelToTheCellBottom() {
        // The crashing input : the level of any block that does not hold liquid.
        assertEquals(-0.5f, Liquid.topOffset(Block.LIQUID_DISABLED), 1e-6f);
        assertEquals(-0.5f, Liquid.topOffset(Block.LIQUID_NONE), 1e-6f);
    }

    @Test
    void clampsLevelsAboveTheMaximum() {
        float full = Liquid.topOffset(Liquid.LEVEL_MAX);
        assertEquals(0.5f, full, 1e-6f);
        assertEquals(full, Liquid.topOffset(Block.LIQUID_SOURCE), 1e-6f);
        assertEquals(full, Liquid.topOffset(99), 1e-6f);
    }

    @Test
    void mapsEveryRealLevelToAnIncreasingSurface() {
        float previous = Liquid.topOffset(Block.LIQUID_NONE);
        for (int level = Block.LIQUID_LEVEL1; level <= Liquid.LEVEL_MAX; level++) {
            float offset = Liquid.topOffset(level);
            assertEquals(true, offset > previous, "level " + level + " must sit above level " + (level - 1));
            previous = offset;
        }
        // A source (mapped to LEVEL5 by Block#getLiquidLevel) does not fill the whole cell : the value
        // FarTerrainState pins its far water plane on.
        assertEquals(0.3f, Liquid.topOffset(Block.LIQUID_LEVEL5), 1e-6f);
    }
}
