package org.delaunois.ialon;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.system.JmeContext;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.delaunois.ialon.state.PlayerState;
import org.delaunois.ialon.support.SceneryTestBuilderApplication;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockSelectionStateTest {

    protected static final int TIMEOUT_IN_MILLIS = 10000;

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @Test
    void testShowMenu() {
        IalonConfig config = new IalonConfig();
        config.setDevMode(true);
        config.setDebugChunks(true);
        config.setPlayerLocation(new Vector3f(0, 100, 0));
        config.setPlayerStartFly(true);

        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);
        config.setTerrainGenerator(new NoiseTerrainGenerator(2, 50));

        SimpleApplication app = new SceneryTestBuilderApplication(config);
        app.setShowSettings(false);
        app.start(JmeContext.Type.Headless);

        PlayerState playerState = null;
        while (playerState == null) {
            playerState = app.getStateManager().getState(PlayerState.class);
        }

        long start = System.currentTimeMillis();
        long duration = 0;
        while (!app.getStateManager().getState(PlayerState.class).isEnabled() && duration < TIMEOUT_IN_MILLIS) {
            duration = System.currentTimeMillis() - start;
        }

        assertTrue(duration < TIMEOUT_IN_MILLIS, "The player should have started before timeout");
    }

}
