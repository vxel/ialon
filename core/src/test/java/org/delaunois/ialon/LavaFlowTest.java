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
 * Regression tests for the lava liquid : water/lava incompatibility (flows stop where they meet) and
 * the pure-cell rule (lava does not co-habit with structural blocks, unlike water).
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
    void waterAndLavaStopWhereTheyMeet() {
        init("lava-ut1");

        // Solid floor so the liquids spread horizontally instead of falling.
        for (int x = 3; x <= 8; x++) {
            addBlock(BlockIds.COBBLESTONE, x, FLOOR, Z);
        }

        // Adjacent water and lava sources : each tries to flow into the other and must stop.
        addBlock(BlockIds.WATER_SOURCE, 5, Y, Z);
        addBlock(BlockIds.LAVA_SOURCE, 6, Y, Z);
        waitAllLiquidEnd();

        // Neither source overwrote the other : the boundary holds.
        assertEquals(TypeIds.WATER, blockAt(5, Y, Z).getType(), "water source must remain water");
        assertEquals(TypeIds.LAVA, blockAt(6, Y, Z).getType(), "lava source must remain lava");

        // Each liquid still flowed away from the boundary on its own side.
        Block west = blockAt(4, Y, Z);
        assertNotNull(west, "water should have flowed west");
        assertEquals(TypeIds.WATER, west.getType(), "the cell west of the water source is water");

        Block east = blockAt(7, Y, Z);
        assertNotNull(east, "lava should have flowed east");
        assertEquals(TypeIds.LAVA, east.getType(), "the cell east of the lava source is lava");
    }

    @Test
    void lavaDoesNotCohabitWithStructuralBlocks() {
        init("lava-ut1");

        for (int x = 3; x <= 9; x++) {
            addBlock(BlockIds.COBBLESTONE, x, FLOOR, Z);
        }

        // A non-solid structural block (cross-plane grass) in the lava's path.
        String itemGrass = BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 0);
        addBlock(itemGrass, 7, Y, Z);

        addBlock(BlockIds.LAVA_SOURCE, 5, Y, Z);
        waitAllLiquidEnd();

        // Lava flowed up to the structure...
        assertEquals(TypeIds.LAVA, blockAt(6, Y, Z).getType(), "lava reaches the cell before the structure");

        // ...but does NOT enter the structural cell (pure-cell rule) : it stays the dry grass block.
        Block structure = blockAt(7, Y, Z);
        assertNotNull(structure, "the structural block is still there");
        assertEquals(TypeIds.ITEM_GRASS, structure.getType(), "structure type preserved");
        assertEquals(0, structure.getLiquidLevel(), "no lava logged into the structural block");

        // ...and lava did not leak past the structure.
        assertNull(blockAt(8, Y, Z), "lava must not flow past the structure");
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
