package org.delaunois.ialon.support;

import com.jme3.app.DebugKeysAppState;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.state.BlockSelectionState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.MoonState;
import org.delaunois.ialon.state.ScreenState;
import org.delaunois.ialon.state.SkyState;
import org.delaunois.ialon.state.SplashscreenState;
import org.delaunois.ialon.state.SunState;
import org.delaunois.ialon.state.TimeFactorState;
import org.delaunois.ialon.state.WireframeState;
import org.delaunois.ialon.state.WorldBuilderState;

public class SceneryTestBuilderApplication extends SimpleApplication {

    private final IalonConfig config;

    public SceneryTestBuilderApplication(IalonConfig config) {
        super((AppState[]) null);
        this.config = config;
    }

    @Override
    public void simpleInitApp() {
        stateManager.attach(new SplashscreenState(config));

        IalonInitializer.setupLogging();
        IalonInitializer.setupCamera(this, config);
        IalonInitializer.setupViewPort(this);
        IalonInitializer.setupAtlasManager(this, config);
        IalonInitializer.setupAtlasFont(this, config);
        IalonInitializer.setupBlockFramework(this, config);
        stateManager.attach(IalonInitializer.setupBulletAppState(config));
        stateManager.attach(IalonInitializer.setupChunkSaverState(config));
        stateManager.attach(IalonInitializer.setupPlayerState(config));
        stateManager.attach(IalonInitializer.setupStatsAppState(config));
        stateManager.attach(IalonInitializer.setupChunkManager(config));
        stateManager.attach(IalonInitializer.setupChunkPager(this, config)); // Depends on PlayerState
        stateManager.attach(IalonInitializer.setupPhysicsChunkPager(this, config)); // Depends on PlayerState and BulletAppState
        stateManager.attach(IalonInitializer.setupChunkLiquidManager(config));
        stateManager.attach(new LightingState(config));
        stateManager.attach(new ScreenState(settings, config));
        stateManager.attach(new SunState(config));
        stateManager.attach(new MoonState(config));
        stateManager.attach(new SkyState(config));
        stateManager.attach(new BlockSelectionState(config));
        stateManager.attach(new TimeFactorState(config));
        stateManager.attach(new WorldBuilderState(config));

        IalonInitializer.setupGui(this, config); // Must be after block framework is initialized

        if (config.isDevMode()) {
            stateManager.attach(new IalonDebugState(config));
            stateManager.attach(new DebugKeysAppState());
            stateManager.attach(new WireframeState());
        }
    }
}