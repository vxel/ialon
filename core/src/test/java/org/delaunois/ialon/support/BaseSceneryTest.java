package org.delaunois.ialon.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkResolver;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.ChunkLightManager;
import org.delaunois.ialon.ChunkLiquidManager;
import org.delaunois.ialon.ChunkManager;
import org.delaunois.ialon.EmptyGenerator;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.WorldManager;
import org.delaunois.ialon.ZipFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public abstract class BaseSceneryTest {

    protected static final String SCENERY_DIR = "src/test/resources/scenery/";
    protected static final int MAX_SIMULATION_STEPS = 1000;
    protected static final Vec3i ORIGIN = new Vec3i(0, 0, 0);
    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new JsonFactory());

    protected WorldManager worldManager = null;
    protected Path path = null;

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    public static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @AfterEach
    public void reset() {
        if (worldManager != null && worldManager.getChunkManager() != null) {
            worldManager.getChunkManager().cleanup(10000);
            worldManager = null;
            path = null;
        }
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    public void init(String utPath) {
        path = Paths.get(SCENERY_DIR, utPath);
        IalonConfig config = new IalonConfig();
        config.setSavePath(path);
        config.setTerrainGenerator(new EmptyGenerator());

        ChunkManager chunkManager = ChunkManager.builder()
                .repository(config.getChunkRepository())
                .poolSize(1)
                .build();
        assertNotNull(chunkManager);
        chunkManager.initialize();
        config.setChunkManager(chunkManager);
        worldManager = new WorldManager(chunkManager,
                new ChunkLightManager(config),
                new ChunkLiquidManager(config));

        chunkManager.generateChunk(ORIGIN);
    }

    public void addBlock(String blockName, int x, int y, int z) {
        assertInitialized();
        worldManager.addBlock(new Vector3f(x, y, z), BlocksConfig.getInstance().getBlockRegistry().get(blockName));
    }

    public void removeBlock(int x, int y, int z) {
        assertInitialized();
        worldManager.removeBlock(new Vector3f(x, y, z));
    }

    public void removeSourceBlock(int x, int y, int z) {
        assertInitialized();
        addBlock(BlockIds.GRASS, x, y, z);
        removeBlock(x, y, z);
    }

    public void waitLiquidSimulationEnd() {
        assertInitialized();
        int i = 0;
        while (worldManager.getChunkLiquidManager().queueSize() > 0 && i < MAX_SIMULATION_STEPS) {
            worldManager.getChunkLiquidManager().step();
            i++;
        }
    }

    public void verify(String blockFileName, String message) {
        assertInitialized();
        Chunk actual = worldManager.getChunkManager().getChunk(ORIGIN).orElse(null);
        ZipFileRepository zipFileRepository = new ZipFileRepository(path);
        Chunk expected = zipFileRepository.load(blockFileName);

        assertEquals(toString(expected), toString(actual), message);
    }

    @SuppressWarnings("unused")
    public void save(Vec3i chunkLocation, String name) {
        Chunk chunk = worldManager.getChunkManager().getChunk(chunkLocation).orElse(null);
        ZipFileRepository zipFileRepository = new ZipFileRepository(path);
        zipFileRepository.save(chunk, name);
    }

    public static String toString(Chunk chunk) {
        if (chunk != null) {
            String s;
            try {
                boolean dirty = chunk.isDirty();
                boolean generate = chunk.isGenerated();
                ChunkResolver chunkResolver = chunk.getChunkResolver();
                chunk.setDirty(false);
                chunk.setGenerated(true);
                chunk.setChunkResolver(null);

                s = OBJECT_MAPPER
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(chunk);

                chunk.setDirty(dirty);
                chunk.setGenerated(generate);
                chunk.setChunkResolver(chunkResolver);
                return s;
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    private void assertInitialized() {
        if (path == null || worldManager == null) {
            throw new IllegalStateException("Please initialize the test first with init()");
        }
    }

}
