package org.delaunois.ialon;

import static org.delaunois.ialon.Ialon.IALON_STYLE;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.app.state.BaseAppState;
import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.font.BitmapFont;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Node;
import com.jme3.texture.Texture;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlockRegistry;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.BlocksTheme;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeRegistry;
import org.delaunois.ialon.blocks.TextureAtlasManager;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.event.BasePickState;
import com.simsilica.lemur.style.Styles;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.jme.BitmapFontLoader;
import org.delaunois.ialon.blocks.jme.LayerComparator;
import org.delaunois.ialon.state.ChunkLiquidManagerState;
import org.delaunois.ialon.state.ChunkManagerState;
import org.delaunois.ialon.state.ChunkPagerState;
import org.delaunois.ialon.state.ChunkSaverState;
import org.delaunois.ialon.state.FarTerrainState;
import org.delaunois.ialon.state.FarTreeState;
import org.delaunois.ialon.state.PhysicsChunkPagerState;
import org.delaunois.ialon.state.PlayerState;
import org.delaunois.ialon.state.SplashscreenState;
import org.delaunois.ialon.state.StatsAppState;
import org.delaunois.ialon.state.WorldBuilderState;
import org.delaunois.ialon.ui.Slider;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.delaunois.ialon.blocks.ChunkPager;
import org.delaunois.ialon.blocks.FacesMeshGenerator;
import org.delaunois.ialon.input.IalonKeyMapping;
import org.delaunois.ialon.blocks.PhysicsChunkPager;

@Slf4j
public class IalonInitializer {

    private static final String BACKGROUND = "background";

    private IalonInitializer() {
        // Prevent instanciation
    }

    public static void setupLogging() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    public static void setupCamera(SimpleApplication app) {
        app.getCamera().setFrustumNear(0.1f);
        app.getCamera().setFrustumFar(4000f);
        app.getCamera().setFov(50);
    }

    public static void setupViewPort(SimpleApplication app) {
        // Authored in sRGB, stored linear : the sRGB framebuffer encodes the clear colour on output.
        app.getViewPort().setBackgroundColor(new ColorRGBA().setAsSrgb(0.5f, 0.6f, 0.7f, 1.0f));
        RenderQueue rq = app.getViewPort().getQueue();
        rq.setGeometryComparator(RenderQueue.Bucket.Transparent,
                new LayerComparator(rq.getGeometryComparator(RenderQueue.Bucket.Transparent), -1));
    }

    public static AppState setupFarTerrain(IalonConfig config) {
        return new FarTerrainState(config);
    }

    public static AppState setupFarTree(IalonConfig config) {
        return new FarTreeState(config);
    }

    public static ChunkSaverState setupChunkSaverState(IalonConfig config) {
        ChunkSaverState chunkSaverState = new ChunkSaverState(config);
        if (config.getChunkManager() == null) {
            throw new IllegalStateException("ChunkManager must be initialised before ChunkSaverState !");
        }
        config.getChunkManager().addListener(chunkSaverState);
        return chunkSaverState;
    }

    public static PlayerState setupPlayerState(SimpleApplication app, IalonConfig config) {
        IalonKeyMapping.setup(app.getInputManager());

        PlayerState playerState = new PlayerState(config);
        playerState.setEnabled(false);
        return playerState;
    }

    public static void setupAtlasManager(SimpleApplication app, IalonConfig config) {
        long start = System.nanoTime();
        TextureAtlasManager atlas = config.getTextureAtlasManager();

        BitmapFont font = app.getAssetManager().loadFont(IalonConfig.FONT_PATH);
        Texture fontTexture = font.getPage(0).getTextureParam("ColorMap").getTextureValue();
        atlas.addTexture(fontTexture, TextureAtlasManager.DIFFUSE);
        atlas.addTexture(app.getAssetManager().loadTexture("Models/Wagon/wagon.png"), TextureAtlasManager.DIFFUSE);

        String[] noMiptexPaths = new String[] {
                "Textures/sun.png",
                "Textures/moon.png",
                "Textures/arrowleft.png",
                "Textures/arrowdown.png",
                "Textures/arrowup.png",
                "Textures/arrowright.png",
                "Textures/arrowjump.png",
                "Textures/flight.png",
                "Textures/minus.png",
                "Textures/plus.png",
                "Textures/gear.png",
                "Textures/settings.png",
                "Textures/trash.png"
        };

        for (String texPath : noMiptexPaths) {
            TextureKey key = new TextureKey(texPath);
            key.setGenerateMips(false);
            atlas.addTexture(app.getAssetManager().loadTexture(key), TextureAtlasManager.DIFFUSE);
        }

        // Far-tree silhouettes (one per species) : packed into the same atlas so the distant billboards
        // share the single block-atlas binding. Mips kept on (the atlas builds a mip chain) to limit
        // shimmer on the small, distant sprites. FarTreeState resolves these tiles' UVs by the same keys.
        for (String texPath : FarTreeState.FAR_TREE_TEXTURES) {
            atlas.addTexture(app.getAssetManager().loadTexture(texPath), TextureAtlasManager.DIFFUSE);
        }
        // Timing only covers loading/decoding the source textures registered here ; the actual atlas
        // packing happens lazily on the first getDiffuseMap() (timed separately in TextureAtlasManager).
        log.info("setupAtlasManager: loaded source textures in {} ms",
                (System.nanoTime() - start) / 1_000_000);
    }

    public static void setupAtlasFont(SimpleApplication app, IalonConfig config) {
        // Reload the font using the offset of the atlas tile
        AssetInfo assetInfo = app.getAssetManager().locateAsset(new AssetKey<>(IalonConfig.FONT_PATH));
        BitmapFont font = app.getAssetManager().loadFont(IalonConfig.FONT_PATH);
        BitmapFont atlasFont;
        try {
            atlasFont = BitmapFontLoader.mapAtlasFont(assetInfo, font, config.getTextureAtlasManager().getAtlas());
            config.setFont(atlasFont);
        } catch (IOException e) {
            log.warn("Failed to load atlas font. Using default.", e);
            config.setFont(font);
        }
    }

    public static void setupGui(SimpleApplication app, IalonConfig config) {
        // GuiGlobals is initialised earlier (in Ialon.simpleInitApp) so the splashscreen's Lemur UI can
        // be built and shown before the heavy world initialisation runs.
        BitmapFont font = Optional.ofNullable(config.getFont())
                .orElse(app.getAssetManager().loadFont(IalonConfig.FONT_PATH));

        Optional.ofNullable(app.getStateManager().getState(BasePickState.class))
                .ifPresent(basePickState -> basePickState.removeCollisionRoot(app.getRootNode()));

        // Use the atlas texture
        Texture tex = config.getTextureAtlasManager().getDiffuseMap();
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        Material fontMaterial = font.getPage(0);
        fontMaterial.getTextureParam("ColorMap").setTextureValue(tex);
        fontMaterial.setColor("Color", ColorRGBA.White);

        // Set the remapped font in the default style
        Styles styles = GuiGlobals.getInstance().getStyles();
        styles.setDefault(font);

        float vh = app.getCamera().getHeight() / 100f;

        float buttonSize = 8 * vh;
        styles.getSelector(Slider.VALUE_ID, IALON_STYLE).set(BACKGROUND,
                new QuadBackgroundComponent(app.getAssetManager().loadTexture("Textures/range-filled.png")));
        styles.getSelector(Slider.RANGE_ID, IALON_STYLE).set(BACKGROUND,
                new QuadBackgroundComponent(app.getAssetManager().loadTexture("Textures/range.png")));

        styles.getSelector(Slider.THUMB_ID, IALON_STYLE).set("text", "", false);
        styles.getSelector(Slider.THUMB_ID, IALON_STYLE).set("preferredSize",
                new Vector3f(buttonSize, buttonSize, 0), false);
        styles.getSelector(Slider.THUMB_ID, IALON_STYLE).set(BACKGROUND,
                new QuadBackgroundComponent(app.getAssetManager().loadTexture("Textures/cursor.png")));

        styles.getSelector(Slider.LEFT_ID, IALON_STYLE).set("text", "", false);
        styles.getSelector(Slider.RIGHT_ID, IALON_STYLE).set("text", "", false);

    }

    public static StatsAppState setupStatsAppState(IalonConfig config) {
        StatsAppState statsAppState = new StatsAppState();
        if (config.getFont() != null) {
            statsAppState.setFont(config.getFont());
        }
        statsAppState.setConfig(config);
        statsAppState.setDisplayStatView(config.isDevMode());
        statsAppState.setDisplayFps(config.isShowFps());
        statsAppState.setDisplayPosition(config.isShowPosition());
        return statsAppState;
    }

    public static BulletAppState setupBulletAppState(IalonConfig config) {
        BulletAppState bulletAppState = new BulletAppState();
        bulletAppState.setDebugEnabled(config.isDebugCollisions());
        return bulletAppState;
    }

    public static void setupBlockFramework(SimpleApplication app, IalonConfig config) {
        configureBlocksFramework(app.getAssetManager(), config);
        Node chunkNode = new Node(IalonConfig.CHUNK_NODE_NAME);
        app.getRootNode().attachChild(chunkNode);
    }

    public static AppState setupChunkManager(IalonConfig config) {
        return new ChunkManagerState(config.getChunkManager());
    }

    public static AppState setupChunkPager(SimpleApplication app, IalonConfig config) {
        PlayerState playerState = app.getStateManager().getState(PlayerState.class, true);

        Node chunkNode = (Node) app.getRootNode().getChild(IalonConfig.CHUNK_NODE_NAME);
        if (chunkNode == null) {
            throw new IllegalStateException("Chunk Node is not attached !");
        }

        ChunkPager chunkPager = new ChunkPager(chunkNode, config.getChunkManager());
        chunkPager.setLocation(config.getPlayerLocation());
        chunkPager.setGridLowerBounds(config.getGridLowerBound());
        chunkPager.setGridUpperBounds(config.getGridUpperBound());
        chunkPager.setMaxUpdatePerFrame(100);
        playerState.addListener(chunkPager::setLocation);

        return new ChunkPagerState(chunkPager);
    }

    public static AppState setupPhysicsChunkPager(SimpleApplication app, IalonConfig config) {
        BulletAppState bulletAppState = app.getStateManager().getState(BulletAppState.class, true);
        PlayerState playerState = app.getStateManager().getState(PlayerState.class, true);

        PhysicsSpace physicsSpace = bulletAppState.getPhysicsSpace();
        PhysicsChunkPager physicsChunkPager = new PhysicsChunkPager(physicsSpace, config.getChunkManager());
        physicsChunkPager.setLocation(config.getPlayerLocation());
        physicsChunkPager.setGridLowerBounds(config.getGridLowerBound());
        physicsChunkPager.setGridUpperBounds(config.getGridUpperBound());
        playerState.addListener(physicsChunkPager::setLocation);

        return new PhysicsChunkPagerState(physicsChunkPager);
    }

    public static AppState setupChunkLiquidManager(IalonConfig config) {
        ChunkLiquidManagerState chunkLiquidManagerState = new ChunkLiquidManagerState(config);
        chunkLiquidManagerState.setEnabled(config.isSimulateLiquidFlow());
        return chunkLiquidManagerState;
    }

    /**
     * Attaches, in dependency order, every AppState that depends on the currently loaded world (chunk
     * generation, paging, physics, liquids, the distant horizon and the world builder that enables the
     * player once enough chunks are paged in). Shared by the initial bootstrap ({@link Ialon}) and the
     * runtime world switch (WorldSelectionState), so both build the world the same way. The world's data
     * sources (chunk manager, repository and terrain generator) are pulled lazily from {@code config} ;
     * the caller must have set the target world on the config (and cleared those cached references when
     * switching) before calling this.
     */
    public static void attachWorldStates(SimpleApplication app, IalonConfig config) {
        AppStateManager sm = app.getStateManager();
        sm.attach(setupChunkSaverState(config));
        sm.attach(setupPlayerState(app, config));
        sm.attach(setupChunkManager(config));
        sm.attach(setupChunkPager(app, config)); // Depends on PlayerState
        sm.attach(setupPhysicsChunkPager(app, config)); // Depends on PlayerState and BulletAppState
        sm.attach(setupChunkLiquidManager(config));
        if (config.isFarTerrain()) {
            sm.attach(setupFarTerrain(config)); // Distant horizon, depends on camera + terrain generator
        }
        if (config.isFarTree()) {
            sm.attach(setupFarTree(config)); // Distant trees, depends on camera + terrain generator
        }
        sm.attach(new WorldBuilderState(config)); // Depends on PlayerState + the pagers
    }

    /**
     * Detaches the world-dependent states attached by {@link #attachWorldStates}, in reverse order. Each
     * state's cleanup() releases its world data (pages detached from the chunk node, physics bodies
     * removed, chunk manager pool shut down), leaving the shared scene/physics ready to host a new world.
     */
    public static void detachWorldStates(SimpleApplication app) {
        AppStateManager sm = app.getStateManager();
        detachIfPresent(sm, WorldBuilderState.class);
        detachIfPresent(sm, FarTreeState.class);
        detachIfPresent(sm, FarTerrainState.class);
        detachIfPresent(sm, ChunkLiquidManagerState.class);
        detachIfPresent(sm, PhysicsChunkPagerState.class);
        detachIfPresent(sm, ChunkPagerState.class);
        detachIfPresent(sm, ChunkManagerState.class);
        detachIfPresent(sm, PlayerState.class);
        detachIfPresent(sm, ChunkSaverState.class);
    }

    private static void detachIfPresent(AppStateManager sm, Class<? extends BaseAppState> type) {
        BaseAppState state = sm.getState(type);
        if (state != null) {
            sm.detach(state);
        }
    }

    public static SplashscreenState getOrAttachSplashscreen(SimpleApplication app, IalonConfig config) {
        SplashscreenState splash = app.getStateManager().getState(SplashscreenState.class);
        if (splash == null) {
            splash = new SplashscreenState(config);
            app.getStateManager().attach(splash);
        }
        return splash;
    }

    public static void configureBlocksFramework(AssetManager assetManager, IalonConfig ialonConfig) {
        BlocksConfig.initialize(assetManager, false);
        BlocksConfig blocksConfig = BlocksConfig.getInstance();
        blocksConfig.setGrid(new Vec3i(ialonConfig.getGridSize(), ialonConfig.getGridHeight() * 2 + 1, ialonConfig.getGridSize()));
        blocksConfig.setPhysicsGrid(new Vec3i(ialonConfig.getPhysicsGridSize(), ialonConfig.getPhysicsGridSize(), ialonConfig.getPhysicsGridSize()));
        blocksConfig.setChunkSize(new Vec3i(ialonConfig.getChunkSize(), ialonConfig.getChunkHeight(), ialonConfig.getChunkSize()));
        blocksConfig.getShapeRegistry().registerDefaultShapes();
        blocksConfig.setChunkMeshGenerator(new FacesMeshGenerator(ialonConfig));

        TypeRegistry typeRegistry = blocksConfig.getTypeRegistry();
        typeRegistry.setTheme(new BlocksTheme("Ialon", "/IalonTheme"));
        typeRegistry.setAtlasManager(ialonConfig.getTextureAtlasManager());
        long start = System.nanoTime();
        typeRegistry.registerDefaultMaterials();
        log.info("registerDefaultMaterials: loaded block materials/textures in {} ms",
                (System.nanoTime() - start) / 1_000_000);

        registerIalonBlocks();
    }

    public static void registerIalonBlocks() {
        Collection<String> types = BlocksConfig.getInstance().getTypeRegistry().getAll();
        for (IalonBlock block : IalonBlock.values()) {
            if (!types.contains(block.getType())) {
                BlocksConfig.getInstance().getTypeRegistry().register(block.getType());
            }

            for (String shape : block.getShapes()) {
                registerIalonBlock(block, shape);
            }
        }
    }

    public static void registerIalonBlock(IalonBlock block, String shape) {
        BlockRegistry registry = BlocksConfig.getInstance().getBlockRegistry();
        if (ShapeIds.CUBE.equals(shape)) {
            registry.register(Block.builder()
                    .name(block.getName() != null ? block.getName() : BlockIds.getName(block.getType(), shape))
                    .type(block.getType())
                    .shape(shape)
                    .solid(block.isSolid())
                    .transparent(block.isTransparent())
                    .usingMultipleImages(block.isMultitexture())
                    .torchlight(block.isTorchlight())
                    // Only the CUBE form of a natural-ground type shapes the far terrain (not its slabs/stairs).
                    .terrain(block.isTerrain())
                    .build());
        } else {
            for (byte waterLevel : block.getWaterLevels()) {
                registry.register(Block.builder()
                        .name(block.getName() != null ? block.getName() : BlockIds.getName(block.getType(), shape, waterLevel))
                        .type(block.getType())
                        .shape(shape)
                        .solid(block.isSolid())
                        .transparent(block.isTransparent())
                        .usingMultipleImages(block.isMultitexture())
                        .liquidLevel(waterLevel)
                        .torchlight(block.isTorchlight())
                        .build());
            }
        }
    }

}
