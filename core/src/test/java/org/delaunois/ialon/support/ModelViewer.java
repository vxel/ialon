package org.delaunois.ialon.support;

import com.jme3.app.DebugKeysAppState;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.delaunois.ialon.EmptyGenerator;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.state.BlockSelectionState;
import org.delaunois.ialon.state.ButtonManagerState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.MoonState;
import org.delaunois.ialon.state.ScreenState;
import org.delaunois.ialon.state.SkyState;
import org.delaunois.ialon.state.SunState;
import org.delaunois.ialon.state.TimeFactorState;
import org.delaunois.ialon.state.WireframeState;
import org.delaunois.ialon.state.WorldBuilderState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ModelViewer extends SimpleApplication {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    public static void main(String[] args) {
        Path saveDir = Paths.get("core/src/test/resources/scenery/modelviewer");
        if (args.length > 0) {
            saveDir = Paths.get(args[0]);
        }

        IalonConfig config = new IalonConfig();
        config.setSavePath(saveDir);
        config.setDevMode(true);
        config.setDebugChunks(true);
        config.setPlayerLocation(new Vector3f(8, 11, 8));
        config.setPlayerStartFly(true);

        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);
        config.setTerrainGenerator(new EmptyGenerator());

        SimpleApplication app = new ModelViewer(config);

        AppSettings settings = new AppSettings(false);
        settings.setFrameRate(IalonConfig.FPS_LIMIT);
        settings.setResolution(config.getScreenWidth(), config.getScreenHeight());
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        settings.setUseInput(true);
        settings.setAudioRenderer(null);
        settings.setVSync(false);

        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    private final IalonConfig config;

    public ModelViewer(IalonConfig config) {
        super((AppState[]) null);
        this.config = config;
    }

    @Override
    public void simpleInitApp() {
        config.getInputActionManager().setInputManager(inputManager);

        IalonInitializer.setupLogging();
        IalonInitializer.setupCamera(this);
        IalonInitializer.setupViewPort(this);
        IalonInitializer.setupAtlasManager(this, config);
        IalonInitializer.setupAtlasFont(this, config);
        IalonInitializer.setupBlockFramework(this, config);
        stateManager.attach(IalonInitializer.setupBulletAppState(config));
        stateManager.attach(IalonInitializer.setupChunkSaverState(config));
        stateManager.attach(IalonInitializer.setupPlayerState(this, config));
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
        stateManager.attach(new ButtonManagerState(config));
        stateManager.attach(new BlockSelectionState(config));
        stateManager.attach(new TimeFactorState(config));
        stateManager.attach(new WorldBuilderState(config));

        IalonInitializer.setupGui(this, config); // Must be after block framework is initialized

        if (config.isDevMode()) {
            stateManager.attach(new IalonDebugState(config));
            stateManager.attach(new DebugKeysAppState());
            stateManager.attach(new WireframeState());
        }

        String dir = "Models/Wagon";
        Spatial model = loadModel(dir, "wagon.obj", new Vector3f(8.5f, 11f, 10.5f));
        export(model, "export", "wagon.j3o");
        getRootNode().attachChild(model);

        config.getTextureAtlasManager().dump();
    }

    private Spatial loadModel(String dir, String filename, Vector3f location) {
        Geometry model = (Geometry) getAssetManager().loadModel(dir + "/" + filename);
        model.setLocalTranslation(location);
        return model;
    }

    @SuppressWarnings("unused")
    private void export(Spatial model, String dir, String filename) {
        File file = new File(dir + "/" + filename);
        try {
            BinaryExporter.getInstance().save(model, file);
        } catch (IOException ex) {
            Logger.getLogger(ModelViewer.class.getName()).log(Level.SEVERE, "Error: Failed to export model", ex);
        }
        Logger.getLogger(ModelViewer.class.getName()).log(Level.INFO, "Model exported to " + file.getAbsolutePath());
    }

}
