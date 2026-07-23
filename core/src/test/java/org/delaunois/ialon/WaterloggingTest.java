package org.delaunois.ialon;

import com.jme3.math.Vector3f;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.support.BaseSceneryTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Waterlogging" : a water source placed onto a dry, non-full (non-cube) block floods that block's own
 * cell, co-habiting with it — the structure keeps its shape and type and gains a water source level.
 * A full cube fills its cell and can never be waterlogged.
 */
class WaterloggingTest extends BaseSceneryTest {

    // A fresh, empty world (no saved chunk under this scenery path -> EmptyGenerator).
    private static final String UT_PATH = "waterlog-ut";
    private static final int X = 8;
    private static final int Y = 9;
    private static final int Z = 8;

    @Test
    void waterSourceFloodsDryNonFullBlock() {
        init(UT_PATH);

        Block dryNonCube = findWaterloggableDryBlock();
        assertNotNull(dryNonCube, "test requires a registered dry, non-cube, floodable block");

        assertNull(worldManager.getBlock(new Vector3f(X, Y, Z)), "the target cell must start empty");

        // Place the dry, non-full block, then aim a water source at the very same cell.
        addBlock(dryNonCube.getName(), X, Y, Z);
        Block placed = worldManager.getBlock(new Vector3f(X, Y, Z));
        assertNotNull(placed, "the structural block must be placed");
        assertFalse(placed.isLiquidSource(), "the block starts dry");

        addBlock(BlockIds.WATER_SOURCE, X, Y, Z);

        // The block is now waterlogged : same shape/type, carrying a source — NOT replaced by water.
        Block logged = worldManager.getBlock(new Vector3f(X, Y, Z));
        assertNotNull(logged, "the waterlogged block must still exist");
        assertEquals(dryNonCube.getShape(), logged.getShape(), "the structure shape is preserved");
        assertEquals(dryNonCube.getType(), logged.getType(), "the structure type is preserved");
        assertTrue(logged.isLiquidSource(), "the block now carries a water source");
    }

    @Test
    void waterSourceCannotFloodFullCube() {
        init(UT_PATH);

        // A full cube fills its cell : a water source aimed at it must NOT waterlog it.
        addBlock(BlockIds.GRASS, X, Y, Z);
        addBlock(BlockIds.WATER_SOURCE, X, Y, Z);

        Block after = worldManager.getBlock(new Vector3f(X, Y, Z));
        assertNotNull(after, "the cube must still be there");
        assertEquals(ShapeIds.CUBE, after.getShape(), "the full cube is left untouched");
        assertFalse(after.isLiquidSource(), "a full cube cannot be waterlogged");
    }

    /**
     * First registered block that our waterlogging rule accepts : a non-cube, dry (level 0), floodable
     * block whose {@code type-shape-LIQUID_SOURCE} variant exists — excluding shapes/types that
     * {@link WorldManager#doAddBlock} special-cases (slab, rail, fire, doors).
     */
    private static Block findWaterloggableDryBlock() {
        BlocksConfig config = BlocksConfig.getInstance();
        for (Block b : config.getBlockRegistry().getAll()) {
            if (ShapeIds.CUBE.equals(b.getShape())
                    || ShapeIds.SLAB.equals(b.getShape())
                    || b.getLiquidLevelId() != Block.LIQUID_NONE
                    || TypeIds.WATER.equals(b.getType())
                    || TypeIds.LAVA.equals(b.getType())
                    || TypeIds.FIRE.equals(b.getType())
                    || TypeIds.RAIL.equals(b.getType())
                    || WorldManager.isDoor(b.getType())) {
                continue;
            }
            Block source = config.getBlockRegistry()
                    .get(BlockIds.getName(b.getType(), b.getShape(), Block.LIQUID_SOURCE));
            if (source != null) {
                return b;
            }
        }
        return null;
    }
}
