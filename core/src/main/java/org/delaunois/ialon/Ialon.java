/**
 * Ialon, a block construction game
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.delaunois.ialon;

import com.jme3.app.DebugKeysAppState;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.font.BitmapFont;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
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
import com.simsilica.lemur.event.BasePickState;
import com.simsilica.lemur.style.Styles;
import com.simsilica.mathd.Vec3i;
import com.simsilica.util.LogAdapter;

import org.delaunois.ialon.jme.BitmapFontLoader;
import org.delaunois.ialon.jme.LayerComparator;
import org.delaunois.ialon.serialize.UserSettingsRepository;
import org.delaunois.ialon.state.BlockSelectionState;
import org.delaunois.ialon.state.ChunkLiquidManagerState;
import org.delaunois.ialon.state.ChunkManagerState;
import org.delaunois.ialon.state.ChunkPagerState;
import org.delaunois.ialon.state.ChunkSaverState;
import org.delaunois.ialon.state.GridSettingsState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.MoonState;
import org.delaunois.ialon.state.PhysicsChunkPagerState;
import org.delaunois.ialon.state.PlayerState;
import org.delaunois.ialon.state.ScreenState;
import org.delaunois.ialon.state.SkyState;
import org.delaunois.ialon.state.SplashscreenState;
import org.delaunois.ialon.state.StatsAppState;
import org.delaunois.ialon.state.SunState;
import org.delaunois.ialon.state.TimeFactorState;
import org.delaunois.ialon.state.WireframeState;
import org.delaunois.ialon.state.WorldBuilderState;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;


/**
 * Main class for setting up and starting Ialon
 *
 * @author Cedric de Launois
 */
@Slf4j
public class Ialon extends SimpleApplication {

    public Ialon() {
        super(new LightingState(), new SplashscreenState());
    }

    @Override
    public void simpleInitApp() {
        log.info("Initializing Ialon");

        UserSettingsRepository.loadUserSettings(this);
        setupLogging();
        setupCamera(this);
        setupViewPort(this);
        setupAtlasManager(this);
        setupAtlasFont(this);
        setupBlockFramework(this);
        stateManager.attach(setupBulletAppState());
        stateManager.attach(setupChunkSaverState());
        stateManager.attach(setupPlayerState());
        stateManager.attach(setupStatsAppState());
        stateManager.attach(setupChunkManager());
        stateManager.attach(setupChunkPager(this)); // Depends on PlayerState
        stateManager.attach(setupPhysicsChunkPager(this)); // Depends on PlayerState and BulletAppState
        stateManager.attach(setupChunkLiquidManager());
        stateManager.attach(setupGridSettingsState());
        stateManager.attach(new ScreenState(this.settings));
        stateManager.attach(new SunState());
        stateManager.attach(new MoonState());
        stateManager.attach(new SkyState());
        stateManager.attach(new BlockSelectionState());
        stateManager.attach(new TimeFactorState());
        stateManager.attach(new WorldBuilderState());

        setupGui(this); // Must be after block framework is initialized

        if (IalonConfig.getInstance().isDevMode()) {
            stateManager.attach(new IalonDebugState());
            stateManager.attach(new DebugKeysAppState());
            stateManager.attach(new WireframeState());
        }

        int typeSize = BlocksConfig.getInstance().getTypeRegistry().getAll().size();
        int shapeSize = BlocksConfig.getInstance().getShapeRegistry().getAll().size();
        log.info("{} block types registered", typeSize);
        log.info("{} block shapes registered", shapeSize);
        log.info("{} blocks registered", BlocksConfig.getInstance().getBlockRegistry().size());

        if (IalonConfig.getInstance().isDevMode()) {
            IalonConfig.getInstance().getTextureAtlasManager().dump();
        }
    }

    public static void setupLogging() {
        LogAdapter.initialize();
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

    public static ChunkSaverState setupChunkSaverState() {
        return new ChunkSaverState();
    }

    public static PlayerState setupPlayerState() {
        PlayerState playerState = new PlayerState();
        playerState.setEnabled(false);
        return playerState;
    }

    public static void setupAtlasManager(SimpleApplication app) {
        BitmapFont font = app.getAssetManager().loadFont("Textures/font-default.fnt");
        Texture fontTexture = font.getPage(0).getTextureParam("ColorMap").getTextureValue();

        TextureAtlasManager atlas = IalonConfig.getInstance().getTextureAtlasManager();
        atlas.getAtlas().addTexture(app.getAssetManager().loadTexture("Textures/ground.png"), TextureAtlasManager.DIFFUSE);
        atlas.getAtlas().addTexture(app.getAssetManager().loadTexture("Textures/sun.png"), TextureAtlasManager.DIFFUSE);
        atlas.getAtlas().addTexture(app.getAssetManager().loadTexture("Textures/moon.png"), TextureAtlasManager.DIFFUSE);
        atlas.getAtlas().addTexture(fontTexture, TextureAtlasManager.DIFFUSE);
    }

    public static void setupAtlasFont(SimpleApplication app) {
        // Reload the font using the offset of the atlas tile
        AssetInfo assetInfo = app.getAssetManager().locateAsset(new AssetKey<>("Textures/font-default.fnt"));
        BitmapFont font = app.getAssetManager().loadFont("Textures/font-default.fnt");
        BitmapFont atlasFont;
        try {
            atlasFont = BitmapFontLoader.mapAtlasFont(assetInfo, font, IalonConfig.getInstance().getTextureAtlasManager().getAtlas());
            IalonConfig.getInstance().setFont(atlasFont);
        } catch (IOException e) {
            log.warn("Failed to load atlas font. Using default.", e);
            IalonConfig.getInstance().setFont(font);
        }
    }

    public static void setupGui(SimpleApplication app) {
        GuiGlobals.initialize(app);

        BitmapFont font = Optional.ofNullable(IalonConfig.getInstance().getFont())
                .orElse(app.getAssetManager().loadFont("Textures/font-default.fnt"));

        Optional.ofNullable(app.getStateManager().getState(BasePickState.class))
                .ifPresent(basePickState -> basePickState.removeCollisionRoot(app.getRootNode()));

        // Use the atlas texture
        Texture tex = IalonConfig.getInstance().getTextureAtlasManager().getDiffuseMap();
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        Material fontMaterial = font.getPage(0);
        fontMaterial.getTextureParam("ColorMap").setTextureValue(tex);
        fontMaterial.setColor("Color", ColorRGBA.White);

        // Set the remapped font int the default style
        Styles styles = GuiGlobals.getInstance().getStyles();
        styles.setDefault(font);
    }

    public static StatsAppState setupStatsAppState() {
        StatsAppState statsAppState = new StatsAppState();
        if (IalonConfig.getInstance().isDevMode()) {
            statsAppState.setDisplayStatView(true);
            if (IalonConfig.getInstance().getFont() != null) {
                statsAppState.setFont(IalonConfig.getInstance().getFont());
            }
        } else {
            statsAppState.setDisplayStatView(false);
        }
        return statsAppState;
    }

    public static BulletAppState setupBulletAppState() {
        BulletAppState bulletAppState = new BulletAppState();
        bulletAppState.setDebugEnabled(IalonConfig.getInstance().isDebugCollisions());
        return bulletAppState;
    }

    public static void setupBlockFramework(SimpleApplication app) {
        configureBlocksFramework(app.getAssetManager());
        Node chunkNode = new Node(IalonConfig.CHUNK_NODE_NAME);
        app.getRootNode().attachChild(chunkNode);
    }

    public static AppState setupChunkManager() {
        return new ChunkManagerState(IalonConfig.getInstance().getChunkManager());
    }

    public static AppState setupChunkPager(SimpleApplication app) {
        PlayerState playerState = app.getStateManager().getState(PlayerState.class, true);

        Node chunkNode = (Node) app.getRootNode().getChild(IalonConfig.CHUNK_NODE_NAME);
        if (chunkNode == null) {
            throw new IllegalStateException("Chunk Node is not attached !");
        }

        ChunkPager chunkPager = new ChunkPager(chunkNode, IalonConfig.getInstance().getChunkManager());
        chunkPager.setLocation(playerState.getPlayerLocation());
        chunkPager.setGridLowerBounds(IalonConfig.getInstance().getGridLowerBound());
        chunkPager.setGridUpperBounds(IalonConfig.getInstance().getGridUpperBound());
        chunkPager.setMaxUpdatePerFrame(100);

        return new ChunkPagerState(chunkPager);
    }

    public static AppState setupPhysicsChunkPager(SimpleApplication app) {
        BulletAppState bulletAppState = app.getStateManager().getState(BulletAppState.class, true);
        PlayerState playerState = app.getStateManager().getState(PlayerState.class, true);

        PhysicsSpace physicsSpace = bulletAppState.getPhysicsSpace();
        PhysicsChunkPager physicsChunkPager = new PhysicsChunkPager(physicsSpace, IalonConfig.getInstance().getChunkManager());
        physicsChunkPager.setLocation(playerState.getPlayerLocation());
        physicsChunkPager.setGridLowerBounds(IalonConfig.getInstance().getGridLowerBound());
        physicsChunkPager.setGridUpperBounds(IalonConfig.getInstance().getGridUpperBound());

        return new PhysicsChunkPagerState(physicsChunkPager);
    }

    public static AppState setupChunkLiquidManager() {
        ChunkLiquidManagerState chunkLiquidManagerState = new ChunkLiquidManagerState();
        chunkLiquidManagerState.setEnabled(IalonConfig.getInstance().isSimulateLiquidFlow());
        return chunkLiquidManagerState;
    }

    public static AppState setupGridSettingsState() {
        GridSettingsState gridSettingsState = new GridSettingsState();
        gridSettingsState.setRadius(IalonConfig.getInstance().getGridRadius());
        return gridSettingsState;
    }

    public static void configureBlocksFramework(AssetManager assetManager) {
        BlocksConfig.initialize(assetManager, false);
        BlocksConfig blocksConfig = BlocksConfig.getInstance();
        IalonConfig ialonConfig = IalonConfig.getInstance();
        blocksConfig.setGrid(new Vec3i(ialonConfig.getGridSize(), ialonConfig.getGridHeight() * 2 + 1, ialonConfig.getGridSize()));
        blocksConfig.setPhysicsGrid(new Vec3i(ialonConfig.getPhysicsGridSize(), ialonConfig.getPhysicsGridSize(), ialonConfig.getPhysicsGridSize()));
        blocksConfig.setChunkSize(new Vec3i(ialonConfig.getChunkSize(), ialonConfig.getChunkHeight(), ialonConfig.getChunkSize()));
        blocksConfig.getShapeRegistry().registerDefaultShapes();
        blocksConfig.setChunkMeshGenerator(new FacesMeshGenerator());

        TypeRegistry typeRegistry = blocksConfig.getTypeRegistry();
        typeRegistry.setTheme(new BlocksTheme("Ialon", "/ialon-theme"));
        typeRegistry.setAtlasRepository(ialonConfig.getTextureAtlasManager());
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

    @Override
    public void start() {
        super.start();
        log.info("Starting Ialon");
    }

    @Override
    public void restart() {
        super.restart();
        Optional.ofNullable(stateManager.getState(ScreenState.class)).ifPresent(ScreenState::checkResize);
        log.info("Restarting Ialon");
    }

    @Override
    public void stop() {
        super.stop();
        log.info("Stopping Ialon");
        if (IalonConfig.getInstance().isSaveUserSettingsOnStop()) {
            UserSettingsRepository.saveUserSettings(this);
        }
    }

}
