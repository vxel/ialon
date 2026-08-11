package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkLightManager;
import org.delaunois.ialon.blocks.ChunkLiquidManager;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.WorldEditOverlay;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.blocks.ZipFileRepository;
import org.delaunois.ialon.blocks.generator.FlatTerrainGenerator;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chunk reset ({@code WorldManager.resetColumn}) : a whole chunk column goes back to the terrain the
 * generator produces, wherever in the column the player had edited, while everything outside the column
 * is left alone. The overlay bookkeeping that goes with it (minimap marker dropped, far relief flagged
 * for re-measurement) and the deletion of the saved chunk are checked too.
 *
 * <p>The world is a {@link FlatTerrainGenerator} one : "pristine" is then a known, non-empty terrain, so a
 * reset is distinguishable both from "nothing happened" and from "everything was wiped".</p>
 */
class ChunkResetTest {

    // Ground level of the flat world : y in [0, GROUND] is grass, above it is air.
    private static final int GROUND = 3;
    // Chunks of the column under test. Two are enough to show the reset spans the whole column.
    private static final int COLUMN_HEIGHT = 2;

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION,
                PrimitiveAllocator.class.getName());
    }

    private ChunkManager chunkManager;
    private WorldManager worldManager;
    private WorldEditOverlay overlay;
    private NoiseTerrainGenerator noiseGenerator;

    @BeforeAll
    static void setUpFramework() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @BeforeEach
    void setUp() {
        IalonConfig config = new IalonConfig();
        chunkManager = ChunkManager.builder()
                .generator(new FlatTerrainGenerator(GROUND,
                        BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.GRASS)))
                .poolSize(1)
                .build();
        chunkManager.initialize();
        config.setChunkManager(chunkManager);

        // The overlay records are keyed by the noise generator (canonical column / tree-cell keys), which is
        // independent of the generator the chunks themselves come from — so a flat world can exercise them.
        overlay = new WorldEditOverlay();
        noiseGenerator = new NoiseTerrainGenerator(config.getSeed(), config.getWaterHeight(),
                config.getMaxy(), config.getWorldSize());
        worldManager = new WorldManager(chunkManager, new ChunkLightManager(config),
                new ChunkLiquidManager(config), overlay, noiseGenerator);

        for (int chunkY = 0; chunkY < COLUMN_HEIGHT; chunkY++) {
            chunkManager.generateChunk(new Vec3i(0, chunkY, 0));
        }
    }

    @AfterEach
    void tearDown() {
        chunkManager.cleanup(10000);
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    /** The pristine chunks of the column under test, as the state generates them off the update thread. */
    private List<Chunk> pristineColumn(int chunkX, int chunkZ) {
        List<Chunk> pristine = new ArrayList<>(COLUMN_HEIGHT);
        for (int chunkY = 0; chunkY < COLUMN_HEIGHT; chunkY++) {
            pristine.add(chunkManager.generatePristineChunk(new Vec3i(chunkX, chunkY, chunkZ)));
        }
        return pristine;
    }

    private void addBlock(int x, int y, int z, String blockId) {
        worldManager.addBlock(new Vector3f(x, y, z),
                BlocksConfig.getInstance().getBlockRegistry().get(blockId));
    }

    private boolean hasBlockAt(int x, int y, int z) {
        return worldManager.getBlock(new Vector3f(x, y, z)) != null;
    }

    @Test
    void undoesEveryEditOfTheColumnAtEveryHeight() {
        // A hole dug in the ground, a tower built over it, and a block placed in the column's upper chunk.
        worldManager.removeBlock(new Vector3f(5, GROUND, 5));
        addBlock(5, 8, 5, BlockIds.OAK_LOG);
        addBlock(5, 20, 5, BlockIds.OAK_LOG);
        assertFalse(hasBlockAt(5, GROUND, 5), "the hole must be dug before resetting");
        assertTrue(hasBlockAt(5, 8, 5), "the tower must stand before resetting");
        assertTrue(hasBlockAt(5, 20, 5), "the upper block must stand before resetting");

        Set<Vec3i> touched = worldManager.resetColumn(0, 0, pristineColumn(0, 0));

        assertEquals(COLUMN_HEIGHT, touched.size(), "every chunk of the column must be reported as reset");
        assertTrue(hasBlockAt(5, GROUND, 5), "the dug ground must be back");
        assertFalse(hasBlockAt(5, 8, 5), "the built tower must be gone");
        assertFalse(hasBlockAt(5, 20, 5), "the block in the upper chunk must be gone too");
    }

    @Test
    void restoresTheGeneratedTerrainRatherThanEmptyingTheColumn() {
        worldManager.removeBlock(new Vector3f(5, GROUND, 5));

        worldManager.resetColumn(0, 0, pristineColumn(0, 0));

        assertTrue(hasBlockAt(0, GROUND, 0), "the flat ground is generated terrain and must be there");
        assertTrue(hasBlockAt(15, GROUND, 15), "up to the far corner of the chunk");
        assertFalse(hasBlockAt(0, GROUND + 1, 0), "and nothing above it");
    }

    @Test
    void leavesTheNeighbouringColumnsAlone() {
        chunkManager.generateChunk(new Vec3i(1, 0, 0));
        addBlock(20, 8, 5, BlockIds.OAK_LOG); // chunk (1, 0, 0), just across the border

        worldManager.resetColumn(0, 0, pristineColumn(0, 0));

        assertTrue(hasBlockAt(20, 8, 5), "an edit in the next column must survive the reset");
    }

    @Test
    void reportsNothingWhenTheColumnIsNoLongerLoaded() {
        List<Chunk> pristine = pristineColumn(9, 9); // never generated, so never cached

        assertTrue(worldManager.resetColumn(9, 9, pristine).isEmpty(),
                "a column that is not paged in cannot be reset");
    }

    @Test
    void dropsTheMinimapMarkerAndAsksForAFarReliefRefresh() {
        addBlock(5, 8, 5, BlockIds.OAK_LOG); // any edit marks the column, both ways
        long modifiedKey = noiseGenerator.modifiedColumnKey(0, 0);
        long rawKey = WorldEditOverlay.pack(0, 0);
        assertTrue(overlay.getModifiedColumns().contains(modifiedKey), "the edit must mark the column");
        overlay.clearColumnEdited(rawKey); // as the far terrain does once it has re-measured the column

        worldManager.resetColumn(0, 0, pristineColumn(0, 0));

        assertFalse(overlay.getModifiedColumns().contains(modifiedKey),
                "a reset column carries no edit any more : the minimap must stop tinting it");
        assertTrue(overlay.isColumnEdited(rawKey),
                "the far relief must re-measure the column to drop the overrides the edits had left");
    }

    @Test
    void restoresTheFarBillboardsOfTheTreesItPutsBack() {
        // A chunk the noise generator plants a tree in — the world is wooded in patches, so it is looked up
        // rather than assumed. The voxels themselves are the flat world's : what is under test is the far
        // billboard bookkeeping, which is keyed by the noise generator alone.
        int[] wooded = woodedChunk();
        long cellKey = noiseGenerator.trunkAnchorCellKeyAt(wooded[2], wooded[3]);
        for (int chunkY = 0; chunkY < COLUMN_HEIGHT; chunkY++) {
            chunkManager.generateChunk(new Vec3i(wooded[0], chunkY, wooded[1]));
        }
        overlay.removeTree(cellKey); // the player fells it
        assertTrue(overlay.isTreeRemoved(cellKey), "the felled tree must be recorded before resetting");

        worldManager.resetColumn(wooded[0], wooded[1], pristineColumn(wooded[0], wooded[1]));

        assertFalse(overlay.isTreeRemoved(cellKey),
                "the tree grows back with the terrain, so its far billboard must come back");
    }

    @Test
    void deletingASavedChunkFallsBackToGeneratedTerrain(@TempDir Path savePath) {
        ZipFileRepository repository = new ZipFileRepository(savePath);
        Chunk chunk = chunkManager.getChunk(new Vec3i(0, 0, 0)).orElse(null);
        assertNotNull(chunk);
        assertTrue(repository.save(chunk), "the chunk must be saved before it can be deleted");
        assertNotNull(repository.load(new Vec3i(0, 0, 0)), "and loadable");

        assertTrue(repository.delete(new Vec3i(0, 0, 0)), "the saved chunk must be deleted");

        assertNull(repository.load(new Vec3i(0, 0, 0)),
                "nothing must be left to load : the world falls back to the generated terrain");
        assertFalse(repository.delete(new Vec3i(0, 0, 0)), "deleting again finds nothing to remove");
    }

    /**
     * The first chunk of the world holding a scattered tree, as
     * {@code {chunkX, chunkZ, trunkBlockX, trunkBlockZ}}. The generator's woods are patchy (biome, altitude
     * and density all have a say), so the chunk is searched for rather than assumed.
     */
    private int[] woodedChunk() {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        for (int chunkZ = 0; chunkZ < 16; chunkZ++) {
            for (int chunkX = 0; chunkX < 16; chunkX++) {
                for (int z = chunkZ * chunkSize.z; z < (chunkZ + 1) * chunkSize.z; z++) {
                    for (int x = chunkX * chunkSize.x; x < (chunkX + 1) * chunkSize.x; x++) {
                        if (noiseGenerator.trunkAnchorCellKeyAt(x, z) != -1L) {
                            return new int[]{chunkX, chunkZ, x, z};
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("No procedural tree found near the origin");
    }

    @Test
    void resetOfAnEmptyRequestChangesNothing() {
        assertTrue(worldManager.resetColumn(0, 0, Collections.emptyList()).isEmpty(),
                "with no pristine chunk to install there is nothing to reset");
        assertTrue(hasBlockAt(5, GROUND, 5), "and the world is untouched");
    }
}
