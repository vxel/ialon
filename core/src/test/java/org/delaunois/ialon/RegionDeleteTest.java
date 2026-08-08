package org.delaunois.ialon;

import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.support.BaseSceneryTest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bulk region deletion ({@code WorldManager.removeRegion}) : the whole box is emptied in one pass, the
 * bedrock survives, sunlight floods back into what was carved out, and the liquid simulation refills
 * the hole from whatever still borders it.
 */
class RegionDeleteTest extends BaseSceneryTest {

    // A fresh, empty world (no saved chunk under this scenery path -> EmptyGenerator).
    private static final String UT_PATH = "regiondelete-ut";

    @Test
    void emptiesEveryCellOfTheRegion() {
        init(UT_PATH);

        fill(BlockIds.GRASS, 4, 4, 4, 7, 7, 7);
        assertNotNull(blockAt(5, 5, 5), "the box must be filled before deleting");

        Set<Vec3i> touched = worldManager.removeRegion(new Vec3i(4, 4, 4), new Vec3i(7, 7, 7));

        assertFalse(touched.isEmpty(), "the edited chunk must be reported as touched");
        for (int y = 4; y <= 7; y++) {
            for (int z = 4; z <= 7; z++) {
                for (int x = 4; x <= 7; x++) {
                    assertNull(blockAt(x, y, z), "cell (" + x + "," + y + "," + z + ") must be empty");
                }
            }
        }
    }

    @Test
    void leavesBlocksOutsideTheRegionAlone() {
        init(UT_PATH);

        fill(BlockIds.GRASS, 3, 3, 3, 8, 8, 8);
        worldManager.removeRegion(new Vec3i(4, 4, 4), new Vec3i(7, 7, 7));

        // The one-cell shell around the deleted box must still be there.
        assertNotNull(blockAt(3, 5, 5), "the block just outside on -X survives");
        assertNotNull(blockAt(8, 5, 5), "the block just outside on +X survives");
        assertNotNull(blockAt(5, 3, 5), "the block just below survives");
        assertNotNull(blockAt(5, 8, 5), "the block just above survives");
        assertNotNull(blockAt(5, 5, 3), "the block just outside on -Z survives");
        assertNotNull(blockAt(5, 5, 8), "the block just outside on +Z survives");
    }

    @Test
    void neverRemovesBedrock() {
        init(UT_PATH);

        fill(BlockIds.GRASS, 4, 0, 4, 5, 4, 5);

        worldManager.removeRegion(new Vec3i(4, 0, 4), new Vec3i(5, 4, 5));

        assertNotNull(blockAt(4, 0, 4), "y = 0 is bedrock and must survive");
        assertNotNull(blockAt(4, 1, 4), "y = 1 is bedrock and must survive");
        assertNull(blockAt(4, 2, 4), "y = 2 is above the bedrock and must go");
        assertNull(blockAt(4, 4, 4), "the top of the box must go");
    }

    @Test
    void returnsNothingWhenTheRegionIsEntirelyBedrock() {
        init(UT_PATH);

        fill(BlockIds.GRASS, 4, 0, 4, 5, 1, 5);

        Set<Vec3i> touched = worldManager.removeRegion(new Vec3i(4, 0, 4), new Vec3i(5, 1, 5));

        assertTrue(touched.isEmpty(), "nothing was removable, so no chunk was touched");
        assertNotNull(blockAt(4, 1, 4), "the bedrock is untouched");
    }

    @Test
    void returnsNothingWhenTheRegionIsEmpty() {
        init(UT_PATH);

        Set<Vec3i> touched = worldManager.removeRegion(new Vec3i(4, 4, 4), new Vec3i(7, 7, 7));

        assertTrue(touched.isEmpty(), "an empty box removes nothing");
    }

    @Test
    void restoresSunlightUnderACarvedRoof() {
        init(UT_PATH);

        // A wide roof shadows what is under it. It cannot be made pitch black in a single 16-wide chunk
        // (sunlight only loses one level per horizontal step, so daylight still creeps in from the open
        // sides) — what matters is that the cell below is darkened, then fully lit again once carved.
        fill(BlockIds.GRASS, 2, 6, 2, 13, 6, 13);
        int shadowed = worldManager.getSunlightLevel(new Vector3f(7, 5, 7));
        assertTrue(shadowed < 15, "the cell under the roof starts shadowed, got " + shadowed);

        worldManager.removeRegion(new Vec3i(2, 6, 2), new Vec3i(13, 6, 13));

        assertEquals(15, worldManager.getSunlightLevel(new Vector3f(7, 5, 7)),
                "removing the roof must let the sunlight flood back in");
    }

    @Test
    void liquidFlowsBackIntoTheCarvedHole() {
        init(UT_PATH);

        // A shallow basin : a floor, a ring of walls, and a lone pillar inside it.
        fill(BlockIds.GRASS, 4, 4, 4, 9, 4, 9);
        for (int i = 4; i <= 9; i++) {
            addBlock(BlockIds.GRASS, i, 5, 4);
            addBlock(BlockIds.GRASS, i, 5, 9);
            addBlock(BlockIds.GRASS, 4, 5, i);
            addBlock(BlockIds.GRASS, 9, 5, i);
        }
        addBlock(BlockIds.GRASS, 7, 5, 7);
        addBlock(BlockIds.WATER_SOURCE, 5, 5, 5);
        waitLiquidSimulationEnd();

        Block pillar = blockAt(7, 5, 7);
        assertNotNull(pillar, "the pillar stands before the deletion");
        // A full cube cannot be waterlogged, so it reports LIQUID_DISABLED rather than level 0.
        assertFalse(pillar.getLiquidLevel() > 0, "the pillar is dry, the water flowed around it");

        worldManager.removeRegion(new Vec3i(7, 5, 7), new Vec3i(7, 5, 7));
        waitLiquidSimulationEnd();

        Block flooded = blockAt(7, 5, 7);
        assertNotNull(flooded, "the surrounding water must have flowed into the hole");
        assertTrue(flooded.getLiquidLevel() > 0,
                "the carved cell must carry liquid, got " + flooded.getName());
    }

    @Test
    void recedesTheFlowFedByADeletedSource() {
        init(UT_PATH);

        // A source on a floor : the water spreads over it.
        fill(BlockIds.GRASS, 2, 4, 2, 12, 4, 12);
        addBlock(BlockIds.WATER_SOURCE, 7, 5, 7);
        waitLiquidSimulationEnd();

        Block spread = blockAt(9, 5, 7);
        assertNotNull(spread, "the water must have spread away from the source");
        assertTrue(spread.getLiquidLevel() > 0, "the cell away from the source is wet");

        // Delete only the source cell : the flow it fed must recede.
        worldManager.removeRegion(new Vec3i(7, 5, 7), new Vec3i(7, 5, 7));
        waitLiquidSimulationEnd();

        assertNull(blockAt(9, 5, 7), "the flow the deleted source fed must have receded");
        assertNull(blockAt(7, 5, 7), "the source cell itself is gone");
    }

    private Block blockAt(int x, int y, int z) {
        return worldManager.getBlock(new Vector3f(x, y, z));
    }

    private void fill(String blockName, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    addBlock(blockName, x, y, z);
                }
            }
        }
    }
}
