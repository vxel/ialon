package org.delaunois.ialon;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
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
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlockRegistry;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.BlocksTheme;
import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.TypeRegistry;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.event.BasePickState;
import com.simsilica.lemur.style.Styles;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.jme.BitmapFontLoader;
import org.delaunois.ialon.jme.LayerComparator;
import org.delaunois.ialon.state.ChunkLiquidManagerState;
import org.delaunois.ialon.state.ChunkManagerState;
import org.delaunois.ialon.state.ChunkPagerState;
import org.delaunois.ialon.state.ChunkSaverState;
import org.delaunois.ialon.state.PhysicsChunkPagerState;
import org.delaunois.ialon.state.PlayerState;
import org.delaunois.ialon.state.StatsAppState;
import org.delaunois.ialon.ui.Slider;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.Ialon.IALON_STYLE;

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
        app.getCamera().setFrustumFar(400f);
        app.getCamera().setFov(50);
    }

    public static void setupViewPort(SimpleApplication app) {
        app.getViewPort().setBackgroundColor(new ColorRGBA(0.5f, 0.6f, 0.7f, 1.0f));
        RenderQueue rq = app.getViewPort().getQueue();
        rq.setGeometryComparator(RenderQueue.Bucket.Transparent,
                new LayerComparator(rq.getGeometryComparator(RenderQueue.Bucket.Transparent), -1));
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
        TextureAtlasManager atlas = config.getTextureAtlasManager();

        BitmapFont font = app.getAssetManager().loadFont(IalonConfig.FONT_PATH);
        Texture fontTexture = font.getPage(0).getTextureParam("ColorMap").getTextureValue();
        atlas.addTexture(fontTexture, TextureAtlasManager.DIFFUSE);
        atlas.addTexture(app.getAssetManager().loadTexture("Models/Wagon/wagon.png"), TextureAtlasManager.DIFFUSE);

        String[] noMiptexPaths = new String[] {
                "Textures/ground.png",
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
                "Textures/gear.png"
        };

        for (String texPath : noMiptexPaths) {
            TextureKey key = new TextureKey(texPath);
            key.setGenerateMips(false);
            atlas.addTexture(app.getAssetManager().loadTexture(key), TextureAtlasManager.DIFFUSE);
        }
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
        GuiGlobals.initialize(app);

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
        statsAppState.setDisplayStatView(config.isDevMode());
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

    public static void configureBlocksFramework(AssetManager assetManager, IalonConfig ialonConfig) {
        BlocksConfig.initialize(assetManager, false);
        BlocksConfig blocksConfig = BlocksConfig.getInstance();
        blocksConfig.setGrid(new Vec3i(ialonConfig.getGridSize(), ialonConfig.getGridHeight() * 2 + 1, ialonConfig.getGridSize()));
        blocksConfig.setPhysicsGrid(new Vec3i(ialonConfig.getPhysicsGridSize(), ialonConfig.getPhysicsGridSize(), ialonConfig.getPhysicsGridSize()));
        blocksConfig.setChunkSize(new Vec3i(ialonConfig.getChunkSize(), ialonConfig.getChunkHeight(), ialonConfig.getChunkSize()));
        blocksConfig.getShapeRegistry().registerDefaultShapes();
        blocksConfig.setChunkMeshGenerator(new FacesMeshGenerator(ialonConfig));

        TypeRegistry typeRegistry = blocksConfig.getTypeRegistry();
        typeRegistry.setTheme(new BlocksTheme("Ialon", "/ialon-theme"));
        typeRegistry.setAtlasManager(ialonConfig.getTextureAtlasManager());
        typeRegistry.registerDefaultMaterials();

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
