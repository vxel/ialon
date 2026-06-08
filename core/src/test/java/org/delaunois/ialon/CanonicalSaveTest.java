package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import com.simsilica.mathd.Vec3i;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Validates the finite-world save canonicalization : a chunk saved at any location is stored once,
 * under its wrapped (modulo worldSizeChunks) coordinates, and loading any of its toroidal twins
 * returns the same blocks placed at the requested location. This is what makes an edit consistent on
 * both sides of the seam while keeping the save folder bounded.
 */
class CanonicalSaveTest {

    private static final int N = 256; // world side in chunks

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @Test
    void editIsSharedAcrossWrapAndStoredOnce(@TempDir Path dir) throws Exception {
        ZipFileRepository repo = new ZipFileRepository();
        repo.setPath(dir);
        repo.setWorldSizeChunks(N);

        Vec3i size = BlocksConfig.getInstance().getChunkSize();
        short rockId = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.ROCK).getId();

        // A chunk well outside [0, N) : canonical twin is (300-256, 0, 5) = (44, 0, 5).
        Vec3i raw = new Vec3i(300, 0, 5);
        Chunk chunk = Chunk.createAt(raw);
        short[] blocks = new short[size.x * size.y * size.z];
        blocks[0] = rockId;
        chunk.setBlocks(blocks);
        chunk.setLightMap(new byte[size.x * size.y * size.z]);

        repo.save(chunk);

        // The wrapped twin reads back the same blocks, but located where it was requested.
        Vec3i twin = new Vec3i(44, 0, 5);
        Chunk loadedTwin = repo.load(twin);
        assertNotNull(loadedTwin, "wrapped twin must resolve to the same stored tile");
        assertEquals(twin, loadedTwin.getLocation(), "loaded chunk must carry the requested location");
        assertArrayEquals(blocks, loadedTwin.getBlocks(), "wrapped twin must hold the same edit");

        // The original raw location also reads back the same data.
        Chunk loadedRaw = repo.load(raw);
        assertNotNull(loadedRaw);
        assertEquals(raw, loadedRaw.getLocation());
        assertArrayEquals(blocks, loadedRaw.getBlocks());

        // Stored once : a single file on disk for the whole tile column position.
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            assertEquals(1L, files.count(), "the tile must be stored in a single canonical file");
        }
    }

    @Test
    void infiniteWorldKeepsPerLocationFiles(@TempDir Path dir) throws Exception {
        ZipFileRepository repo = new ZipFileRepository();
        repo.setPath(dir);
        repo.setWorldSizeChunks(0); // infinite : no canonicalization

        Vec3i size = BlocksConfig.getInstance().getChunkSize();
        short rockId = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.ROCK).getId();
        byte[] light = new byte[size.x * size.y * size.z];

        for (Vec3i loc : new Vec3i[]{new Vec3i(300, 0, 5), new Vec3i(44, 0, 5)}) {
            Chunk chunk = Chunk.createAt(loc);
            short[] blocks = new short[size.x * size.y * size.z];
            blocks[0] = rockId;
            chunk.setBlocks(blocks);
            chunk.setLightMap(light);
            repo.save(chunk);
        }

        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            assertEquals(2L, files.count(), "infinite world stores each location separately");
        }
    }
}
