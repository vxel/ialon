package org.delaunois.ialon;

import com.jme3.math.Vector3f;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.ChunkLiquidManager;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.support.BaseSceneryTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the lava liquid : water/lava incompatibility (where the two meet, the water
 * cell solidifies into gravel_dark and the lava stays liquid — no mixing) and the pure-cell rule
 * (lava does not co-habit with structural blocks, unlike water).
 */
class LavaFlowTest extends BaseSceneryTest {

    private static final int Y = 5;       // liquid layer
    private static final int Z = 8;
    private static final int FLOOR = 4;   // solid floor just below the liquid layer

    /** Drains BOTH the water (+ shared removal) and the lava flow queues until everything settles. */
    private void waitAllLiquidEnd() {
        ChunkLiquidManager m = worldManager.getChunkLiquidManager();
        int i = 0;
        while ((m.queueSize() > 0 || m.lavaQueueSize() > 0) && i < MAX_SIMULATION_STEPS) {
            m.step();
            m.stepLava();
            i++;
        }
    }

    private Block blockAt(int x, int y, int z) {
        return worldManager.getBlock(new Vector3f(x, y, z));
    }

    @Test
    void waterSolidifiesWhereItMeetsLava() {
        init("lava-ut1");

        // Solid floor so the liquids spread horizontally instead of falling.
        for (int x = 3; x <= 8; x++) {
            addBlock(BlockIds.COBBLESTONE, x, FLOOR, Z);
        }

        // Adjacent water and lava sources. Where the two liquids meet, the water cell solidifies into
        // gravel_dark (Minecraft-style lava + water → stone) while the lava stays liquid — no mixing.
        addBlock(BlockIds.WATER_SOURCE, 5, Y, Z);
        addBlock(BlockIds.LAVA_SOURCE, 6, Y, Z);
        waitAllLiquidEnd();

        // The water cell at the boundary froze into gravel_dark ; the lava source stayed lava.
        assertEquals(TypeIds.GRAVEL_DARK, blockAt(5, Y, Z).getType(),
                "the water cell meeting lava solidifies into gravel_dark");
        assertEquals(TypeIds.LAVA, blockAt(6, Y, Z).getType(), "lava source must remain lava");

        // Each liquid still flowed away from the boundary on its own side, and neither mixed into the
        // other : water stayed water to the west, lava stayed lava to the east.
        Block west = blockAt(4, Y, Z);
        assertNotNull(west, "water should have flowed west");
        assertEquals(TypeIds.WATER, west.getType(), "the cell west of the boundary is water");

        Block east = blockAt(7, Y, Z);
        assertNotNull(east, "lava should have flowed east");
        assertEquals(TypeIds.LAVA, east.getType(), "the cell east of the lava source is lava");
    }

    @Test
    void removingLavaSourceRecedesTheWholeFlow() {
        init("lava-ut1");

        for (int x = 3; x <= 9; x++) {
            addBlock(BlockIds.COBBLESTONE, x, FLOOR, Z);
        }

        // A lava source that flows out horizontally.
        addBlock(BlockIds.LAVA_SOURCE, 5, Y, Z);
        waitAllLiquidEnd();
        assertEquals(TypeIds.LAVA, blockAt(5, Y, Z).getType(), "lava source placed");
        assertNotNull(blockAt(6, Y, Z), "lava flowed out before removal");

        // Removing the source with the delete button must clear the source AND recede the flow it fed.
        worldManager.removeBlock(new Vector3f(5, Y, Z));
        waitAllLiquidEnd();

        assertNull(blockAt(5, Y, Z), "the removed lava source is gone");
        assertNull(blockAt(6, Y, Z), "the lava it fed has receded");
        assertNull(blockAt(7, Y, Z), "the lava it fed has receded");
    }

    @Test
    void lavaDestroysNonSolidItems() {
        init("lava-ut1");

        for (int x = 3; x <= 9; x++) {
            addBlock(BlockIds.COBBLESTONE, x, FLOOR, Z);
        }

        // A non-solid item (cross-plane grass) in the lava's path : lava must burn it away and flow on.
        String itemGrass = BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 0);
        addBlock(itemGrass, 7, Y, Z);

        addBlock(BlockIds.LAVA_SOURCE, 5, Y, Z);
        waitAllLiquidEnd();

        // Lava flowed up to the item...
        assertEquals(TypeIds.LAVA, blockAt(6, Y, Z).getType(), "lava reaches the cell before the item");

        // ...destroyed it (the cell is now lava, not grass)...
        Block destroyed = blockAt(7, Y, Z);
        assertNotNull(destroyed, "the item cell is now occupied by lava");
        assertEquals(TypeIds.LAVA, destroyed.getType(), "lava replaced the destroyed item");

        // ...and flowed past where the item used to be.
        Block past = blockAt(8, Y, Z);
        assertNotNull(past, "lava should have flowed past the destroyed item");
        assertEquals(TypeIds.LAVA, past.getType(), "the cell past the item is lava");
    }

    @Test
    void lavaStopsAgainstSolidBlocks() {
        init("lava-ut1");

        for (int x = 3; x <= 9; x++) {
            addBlock(BlockIds.COBBLESTONE, x, FLOOR, Z);
        }

        // A solid block (a cobblestone slab, which allows liquid levels) in the lava's path : lava must
        // NOT co-habit with it (pure-cell rule) and must NOT destroy it (only non-solid items burn).
        String solidSlab = BlockIds.getName(TypeIds.COBBLESTONE, ShapeIds.SLAB, 0);
        addBlock(solidSlab, 7, Y, Z);

        addBlock(BlockIds.LAVA_SOURCE, 5, Y, Z);
        waitAllLiquidEnd();

        // Lava flowed up to the solid block...
        assertEquals(TypeIds.LAVA, blockAt(6, Y, Z).getType(), "lava reaches the cell before the solid block");

        // ...but does NOT enter its cell and does NOT destroy it.
        Block solid = blockAt(7, Y, Z);
        assertNotNull(solid, "the solid block is still there");
        assertEquals(TypeIds.COBBLESTONE, solid.getType(), "solid block type preserved");
        assertEquals(0, solid.getLiquidLevel(), "no lava logged into the solid block");

        // ...and lava did not leak past it.
        assertNull(blockAt(8, Y, Z), "lava must not flow past the solid block");
    }

    @Test
    void waterDoesCohabitWithStructuralBlocks() {
        // Contrast with lava : water DOES flow around/through a structural block (unchanged behaviour).
        init("lava-ut1");

        for (int x = 3; x <= 9; x++) {
            addBlock(BlockIds.COBBLESTONE, x, FLOOR, Z);
        }
        String itemGrass = BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 0);
        addBlock(itemGrass, 7, Y, Z);

        addBlock(BlockIds.WATER_SOURCE, 5, Y, Z);
        waitAllLiquidEnd();

        Block structure = blockAt(7, Y, Z);
        assertNotNull(structure, "the structural block is still there");
        assertEquals(TypeIds.ITEM_GRASS, structure.getType(), "structure type preserved");
        assertTrue(structure.getLiquidLevel() > 0, "water is logged into the structural block");
    }
}
