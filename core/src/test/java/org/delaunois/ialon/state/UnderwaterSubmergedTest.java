package org.delaunois.ialon.state;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppStateManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.post.SceneProcessor;
import com.jme3.renderer.ViewPort;
import com.jme3.util.SafeArrayList;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ChunkManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins down {@link UnderwaterState#isSubmerged()}, which {@link AmbientSoundState} uses to silence the
 * wildlife under water.
 *
 * The subtlety worth locking is that being inside a water CELL is not the same as being under the
 * water : a liquid block only fills part of its cell (a source fills 0.8 of it), so an eye in the top
 * of a surface cell is above the water line and must not count as submerged. Get that wrong and the
 * birds cut out a block too early — or, worse, the underwater fog does.
 */
class UnderwaterSubmergedTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION,
                PrimitiveAllocator.class.getName());
    }

    /** Grid Y of the water cell every case below uses. */
    private static final int WATER_CELL_Y = 10;

    private UnderwaterState state;
    private IalonConfig config;
    private Camera camera;
    private ChunkManager chunkManager;

    @BeforeAll
    static void setUpBlocks() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @BeforeEach
    void setUp() {
        config = new IalonConfig();
        chunkManager = mock(ChunkManager.class);
        config.setChunkManager(chunkManager);

        camera = new Camera(64, 64);
        ViewPort viewPort = mock(ViewPort.class);
        when(viewPort.getProcessors()).thenReturn(new SafeArrayList<>(SceneProcessor.class));

        // BaseAppState.getStateManager() delegates to app.getStateManager(), so stubbing that is what
        // makes the state's own lookups work. It returns no SkyState, which the fog colour code allows.
        AppStateManager stateManager = mock(AppStateManager.class);
        SimpleApplication app = mock(SimpleApplication.class);
        when(app.getCamera()).thenReturn(camera);
        when(app.getViewPort()).thenReturn(viewPort);
        when(app.getAssetManager()).thenReturn(new DesktopAssetManager(true));
        when(app.getStateManager()).thenReturn(stateManager);

        state = new UnderwaterState(config);
        state.initialize(stateManager, app);
    }

    /** Puts {@code block} (or nothing) at every probed position and looks from {@code eyeY}. */
    private boolean submergedAt(float eyeY, Block block) {
        when(chunkManager.getBlock(any(Vector3f.class))).thenReturn(Optional.ofNullable(block));
        camera.setLocation(new Vector3f(0.5f, eyeY, 0.5f));
        state.update(0.05f);
        return state.isSubmerged();
    }

    private static Block water() {
        return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.WATER_SOURCE);
    }

    private static Block solid() {
        return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.DIRT);
    }

    /** World Y of the top of the water cell's contents, from the same model UnderwaterState uses. */
    private static float waterLineY() {
        float scale = BlocksConfig.getInstance().getBlockScale();
        return (WATER_CELL_Y + 0.5f
                + org.delaunois.ialon.blocks.shapes.Liquid.topOffset(water().getLiquidLevel())) * scale;
    }

    @Test
    void notSubmergedInOpenAir() {
        assertFalse(submergedAt(WATER_CELL_Y + 4f, null), "no block at the eye : nothing to be under");
    }

    @Test
    void notSubmergedInsideASolidBlock() {
        // The camera routinely clips into the wall of a freshly dug tunnel. Such a block carries
        // LIQUID_DISABLED, which must never be treated as a water level.
        assertFalse(submergedAt(WATER_CELL_Y + 0.5f, solid()), "a dirt block is not water");
    }

    @Test
    void submergedWellBelowTheWaterLine() {
        assertTrue(submergedAt(waterLineY() - 0.4f, water()));
    }

    @Test
    void notSubmergedInTheAirGapAtTheTopOfASurfaceWaterCell() {
        // The heart of it : still inside the water CELL, but above the water it actually holds.
        float scale = BlocksConfig.getInstance().getBlockScale();
        float cellTop = (WATER_CELL_Y + 1f) * scale;
        assertTrue(cellTop > waterLineY(), "test is vacuous unless the cell has an air gap on top");
        assertFalse(submergedAt(waterLineY() + 0.05f, water()),
                "the eye is in the cell but above the water line");
    }

    @Test
    void disablingClearsTheFlagSoCallersDoNotSeeAStaleYes() {
        assertTrue(submergedAt(waterLineY() - 0.4f, water()));
        state.setEnabled(false);
        assertFalse(state.isSubmerged(), "a disabled state must not keep reporting 'submerged' forever");
    }
}
