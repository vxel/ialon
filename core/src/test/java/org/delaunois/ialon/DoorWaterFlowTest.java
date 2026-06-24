package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ChunkLightManager;
import org.delaunois.ialon.blocks.ChunkLiquidManager;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.blocks.generator.EmptyGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Water flowing through an OPEN door must drain when the door is CLOSED again (as if a full block had
 * been placed in the passage) — and this must hold whichever side the water comes from.
 */
class DoorWaterFlowTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    private WorldManager world;

    @Test
    void closingDrainsWaterComingFromTheNorth() {
        // Source north of the door (z=1), downstream to the south (z=5).
        runScenario(1, 5);
    }

    @Test
    void closingDrainsWaterComingFromTheSouth() {
        // Source south of the door (z=6), downstream to the north (z=2).
        runScenario(6, 2);
    }

    /**
     * Builds a sealed 1-wide horizontal pipe along Z at x=5, y=2 with a door at z=3, puts a water
     * source at {@code sourceZ}, and checks : closed door blocks, open door flows to {@code downstreamZ},
     * closing again drains it.
     */
    private void runScenario(int sourceZ, int downstreamZ) {
        IalonConfig config = new IalonConfig();
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);
        config.setTerrainGenerator(new EmptyGenerator());
        ChunkManager cm = ChunkManager.builder().poolSize(1).generator(new EmptyGenerator()).build();
        cm.initialize();
        config.setChunkManager(cm);
        world = new WorldManager(cm, new ChunkLightManager(config), new ChunkLiquidManager(config));
        cm.generateChunk(new Vec3i(0, 0, 0));

        for (int z = 0; z <= 7; z++) {
            add(BlockIds.GRASS, 5, 1, z);   // floor
            add(BlockIds.GRASS, 4, 2, z);   // west wall
            add(BlockIds.GRASS, 6, 2, z);   // east wall
        }
        add(BlockIds.GRASS, 5, 2, 0);       // north cap
        add(BlockIds.GRASS, 5, 2, 7);       // south cap

        // Closed door across the pipe at z=3 (door_north covers the SOUTH face).
        add("door_left-door_north-0", 5, 2, 3);
        add(BlockIds.WATER_SOURCE, 5, 2, sourceZ);
        step();
        assertEquals(0, level(5, 2, downstreamZ), "closed door should block flow to z=" + downstreamZ);

        world.toggleDoor(new Vector3f(5, 2, 3));   // open
        step();
        assertTrue(level(5, 2, downstreamZ) > 0, "open door should let water flow to z=" + downstreamZ);

        world.toggleDoor(new Vector3f(5, 2, 3));   // close
        step();
        assertEquals(0, level(5, 2, downstreamZ), "closing must drain downstream water at z=" + downstreamZ);
    }

    private void add(String blockName, int x, int y, int z) {
        world.addBlock(new Vector3f(x, y, z), BlocksConfig.getInstance().getBlockRegistry().get(blockName));
    }

    private int level(int x, int y, int z) {
        Block b = world.getChunkManager().getBlock(new Vector3f(x, y, z)).orElse(null);
        return b == null ? 0 : b.getLiquidLevel();
    }

    private void step() {
        int i = 0;
        while (world.getChunkLiquidManager().queueSize() > 0 && i < 100000) {
            world.getChunkLiquidManager().step();
            i++;
        }
    }
}
