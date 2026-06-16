package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration check for {@link WorldManager#groundHeight} — the scan {@code FarTerrainState} uses to
 * re-measure a far-terrain sample from the live world. The earlier bug wrote height 0 (a failed scan)
 * which the far shader rendered as deep water ; this verifies the scan now returns the real ground
 * height (close to the generator's procedural surface), so the relief override actually works.
 */
class GroundHeightTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    private static final int CEILING = 112; // gridHeight(7) * chunkHeight(16)

    @BeforeAll
    static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
        BlocksConfig.getInstance().setChunkSize(new Vec3i(16, 16, 16));
    }

    @Test
    void groundHeightMatchesGeneratorSurfaceOnLand() {
        NoiseTerrainGenerator gen = new NoiseTerrainGenerator(8969L, 30f, CEILING, 4096f); // world-12 params
        ChunkManager cm = ChunkManager.builder().poolSize(1).generator(gen).build();
        cm.initialize();

        // Find a clearly-land column (surface well above the water line at 30).
        int landX = Integer.MIN_VALUE;
        int landZ = 0;
        float expected = 0f;
        outer:
        for (int x = 0; x < 512; x += 7) {
            for (int z = 0; z < 512; z += 7) {
                float h = gen.getHeight(new Vector3f(x, 0, z));
                if (h > 34f && h < CEILING - 4f) {
                    landX = x;
                    landZ = z;
                    expected = h;
                    break outer;
                }
            }
        }
        if (landX == Integer.MIN_VALUE) {
            fail("no land column found to test");
        }

        // Page in the whole vertical column so the scan sees the surface.
        Vec3i chunk = ChunkManager.getChunkLocation(new Vector3f(landX, 0, landZ));
        for (int cy = 0; cy <= CEILING / 16; cy++) {
            cm.generateChunk(new Vec3i(chunk.x, cy, chunk.z));
        }

        float ground = WorldManager.groundHeight(cm, landX, landZ, CEILING);
        assertFalse(Float.isNaN(ground), "the scan must find the loaded ground, not give up (NaN)");
        assertTrue(ground > 30f, "land ground must sit above the water line, not at 0 (the old bug)");
        assertTrue(Math.abs(ground - expected) <= 2f,
                "ground " + ground + " should match the generator surface " + expected);

        // Only natural-ground cubes shape the terrain : a building block above the ground is ignored,
        // a stacked dirt (terrain) block raises it. This is the "terrain" property in action.
        Block bricks = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(TypeIds.BRICKS, ShapeIds.CUBE));
        Block dirt = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(TypeIds.DIRT, ShapeIds.CUBE));
        assertFalse(bricks.isTerrain(), "a building block must not be flagged terrain");
        assertTrue(dirt.isTerrain(), "a dirt cube must be flagged terrain");

        int topY = (int) ground - 1; // topmost terrain block
        placeCube(cm, landX, topY + 2, landZ, bricks);
        assertEquals(ground, WorldManager.groundHeight(cm, landX, landZ, CEILING), 0.001f,
                "a building block above the ground must NOT raise the measured terrain height");

        placeCube(cm, landX, topY + 1, landZ, dirt);
        assertEquals(ground + 1f, WorldManager.groundHeight(cm, landX, landZ, CEILING), 0.001f,
                "stacking a dirt (terrain) block must raise the measured terrain height");
    }

    private static void placeCube(ChunkManager cm, int x, int y, int z, Block block) {
        Chunk chunk = cm.getChunk(ChunkManager.getChunkLocation(new Vector3f(x, y, z)))
                .orElseThrow(() -> new AssertionError("chunk not loaded for " + x + "," + y + "," + z));
        chunk.addBlock(chunk.toLocalLocation(ChunkManager.getBlockLocation(new Vector3f(x, y, z))), block);
    }
}
