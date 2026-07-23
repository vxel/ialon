package org.delaunois.ialon.blocks;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Digging underground must keep the chunks the cave exposes meshed. A fully opaque chunk enclosed by
 * fully opaque neighbours is skipped at mesh time (it has no visible face) : this is only valid as long
 * as the {@code full}/{@code fullyOpaque} flags follow the player's edits. When they go stale, the chunk
 * the player digs into — and the rock next to it — are skipped, leaving a see-through hole in the world
 * (the far terrain shows through the cave walls).
 */
class ChunkDigExposureTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    private ChunkManager chunkManager;
    private WorldManager worldManager;
    private Block rock;
    private Vec3i chunkSize;

    @BeforeEach
    void setUp() {
        IalonConfig config = new IalonConfig();
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);
        rock = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.ROCK);
        chunkSize = BlocksConfig.getInstance().getChunkSize();

        chunkManager = ChunkManager.builder().poolSize(1).build();
        chunkManager.initialize();
        config.setChunkManager(chunkManager);
        worldManager = new WorldManager(chunkManager,
                new ChunkLightManager(config),
                new ChunkLiquidManager(config));

        // Solid rock all around : the underground situation where every chunk is skipped at mesh time.
        for (int x = -1; x <= 2; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Chunk chunk = solidChunk(new Vec3i(x, y, z));
                    chunkManager.getCache().put(chunk);
                    chunk.setChunkResolver(chunkManager.getCache());
                }
            }
        }
    }

    private Chunk solidChunk(Vec3i location) {
        Chunk chunk = Chunk.createAt(location);
        for (int x = 0; x < chunkSize.x; x++) {
            for (int y = 0; y < chunkSize.y; y++) {
                for (int z = 0; z < chunkSize.z; z++) {
                    chunk.addBlock(x, y, z, rock);
                }
            }
        }
        chunk.update();
        return chunk;
    }

    @Test
    void diggingAtAChunkBorderMeshesBothChunks() {
        // Dig the last block of chunk (0,0,0) along x : the hole exposes a face of chunk (1,0,0).
        worldManager.removeBlock(new Vector3f(chunkSize.x - 0.5f, 8.5f, 8.5f));

        Chunk dug = chunkManager.getChunk(new Vec3i(0, 0, 0)).orElse(null);
        Chunk exposed = chunkManager.getChunk(new Vec3i(1, 0, 0)).orElse(null);
        assertNotNull(dug);
        assertNotNull(exposed);

        assertNotNull(dug.getNode(), "the dug chunk must be meshed");
        assertFalse(dug.getNode() instanceof EmptyNode, "the dug chunk must be meshed, not skipped");
        assertNotNull(exposed.getNode(), "the chunk exposed by the dig must be meshed");
        assertFalse(exposed.getNode() instanceof EmptyNode,
                "the chunk exposed by the dig must be meshed, not skipped");
    }

    @Test
    void skippedOpaqueChunkStillOccludesInTheVisibilityGraph() {
        chunkManager.requestMeshChunks(Collections.singletonList(new Vec3i(0, 0, 0)));

        Chunk chunk = chunkManager.getChunk(new Vec3i(0, 0, 0)).orElse(null);
        assertNotNull(chunk);
        assertTrue(chunk.getNode() instanceof EmptyNode, "a buried opaque chunk renders nothing");
        assertEquals(0, chunk.getFaceConnectivity(), "solid rock connects none of its faces");
        assertEquals(0, (int) chunk.getNode().getUserData(Chunk.USERDATA_FACE_CONNECTIVITY),
                "the cave-culling BFS must see the skipped chunk as an occluder");
    }

    @Test
    void removingABlockClearsTheFullAndOpaqueFlags() {
        Chunk chunk = chunkManager.getChunk(new Vec3i(0, 0, 0)).orElse(null);
        assertNotNull(chunk);
        assertTrue(chunk.isFull());
        assertTrue(chunk.isFullyOpaque());

        chunk.removeBlock(8, 8, 8);

        assertFalse(chunk.isFull(), "a chunk with a dug-out block is no longer full");
        assertFalse(chunk.isFullyOpaque(), "a chunk with a dug-out block no longer occludes its neighbours");
    }

    @Test
    void addingATransparentBlockClearsTheOpaqueFlag() {
        Chunk chunk = chunkManager.getChunk(new Vec3i(0, 0, 0)).orElse(null);
        assertNotNull(chunk);

        Block window = BlocksConfig.getInstance().getBlockRegistry().get("window");
        assertNotNull(window);
        chunk.addBlock(8, 8, 8, window);

        assertTrue(chunk.isFull(), "the chunk still has no air");
        assertFalse(chunk.isFullyOpaque(), "a transparent block makes the chunk see-through");
    }
}
