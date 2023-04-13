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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.app.DebugKeysAppState;
import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.font.BitmapFont;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
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
import org.delaunois.ialon.serialize.PlayerStateDTO;
import org.delaunois.ialon.serialize.PlayerStateRepository;
import org.delaunois.ialon.state.BlockSelectionState;
import org.delaunois.ialon.state.ChunkLiquidManagerState;
import org.delaunois.ialon.state.ChunkManagerState;
import org.delaunois.ialon.state.ChunkPagerState;
import org.delaunois.ialon.state.GridSettingsState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.MoonState;
import org.delaunois.ialon.state.PhysicsChunkPagerState;
import org.delaunois.ialon.state.PlayerState;
import org.delaunois.ialon.state.SkyState;
import org.delaunois.ialon.state.SplashscreenState;
import org.delaunois.ialon.state.StatsAppState;
import org.delaunois.ialon.state.SunState;
import org.delaunois.ialon.state.TimeFactorState;
import org.delaunois.ialon.state.WireframeState;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.Config.CHUNK_HEIGHT;
import static org.delaunois.ialon.Config.CHUNK_POOLSIZE;
import static org.delaunois.ialon.Config.CHUNK_SIZE;
import static org.delaunois.ialon.Config.DEBUG_COLLISIONS;
import static org.delaunois.ialon.Config.DEV_MODE;
import static org.delaunois.ialon.Config.GRID_HEIGHT;
import static org.delaunois.ialon.Config.GRID_LOWER_BOUND;
import static org.delaunois.ialon.Config.GRID_SIZE;
import static org.delaunois.ialon.Config.GRID_UPPER_BOUND;
import static org.delaunois.ialon.Config.MAX_UPDATE_PER_FRAME;
import static org.delaunois.ialon.Config.PHYSICS_GRID_SIZE;
import static org.delaunois.ialon.Config.SIMULATE_LIQUID_FLOW;

/**
 * @author Cedric de Launois
 *
 */
@Slf4j
public class Ialon extends SimpleApplication implements ActionListener {

    private static final String SAVEDIR = "./save";
    private static Path filepath = FileSystems.getDefault().getPath(SAVEDIR);

    private static final String ACTION_SWITCH_MOUSELOCK = "switch-mouselock";
    private static final String ACTION_TOGGLE_TIME_RUN = "toggle-time-run";
    private static final String ACTION_TOGGLE_FULLSCREEN = "toggle-fullscreen";

    @Getter
    @Setter
    private boolean saveUserPreferencesOnStop = true;

    @Getter
    private Node chunkNode;

    @Getter
    private ChunkManager chunkManager;

    @Getter
    private TerrainGenerator terrainGenerator;

    @Getter
    private ZipFileRepository fileRepository;

    @Getter
    private PlayerStateRepository playerStateRepository;

    @Getter
    private TextureAtlasManager atlasManager;

    @Getter
    private boolean mouselocked = false;

    @Getter
    private final long startTime = System.currentTimeMillis();

    private PlayerState playerState;
    private boolean checkResize = false;
    private int camHeight = 0;
    private int camWidth = 0;
    private int pagesAttached = 0;
    private int physicPagesAttached = 0;
    private ExecutorService executorService;

    public static void main(String[] args) {
        LogAdapter.initialize();
    }

    public static void setFilePath(Path path) {
        filepath = path;
    }

    public Ialon() {
        super(new LightingState());
    }

    @Override
    public void simpleInitApp() {
        log.info("Initializing Ialon");

        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        GuiGlobals.initialize(this);
        cam.setFrustumNear(0.1f);
        cam.setFrustumFar(400f);
        cam.setFov(50);

        stateManager.attach(new SplashscreenState());

        executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("save").build());

        viewPort.setBackgroundColor(new ColorRGBA(0.5f, 0.6f, 0.7f, 1.0f));
        RenderQueue rq = viewPort.getQueue();
        rq.setGeometryComparator(RenderQueue.Bucket.Transparent,
                new LayerComparator(rq.getGeometryComparator(RenderQueue.Bucket.Transparent), -1));

        BitmapFont font = assetManager.loadFont("Textures/font-default.fnt");
        Texture fontTexture = font.getPage(0).getTextureParam("ColorMap").getTextureValue();

        atlasManager = new TextureAtlasManager();
        atlasManager.getAtlas().addTexture(assetManager.loadTexture("Textures/ground.png"), TextureAtlasManager.DIFFUSE);
        atlasManager.getAtlas().addTexture(assetManager.loadTexture("Textures/sun.png"), TextureAtlasManager.DIFFUSE);
        atlasManager.getAtlas().addTexture(assetManager.loadTexture("Textures/moon.png"), TextureAtlasManager.DIFFUSE);
        atlasManager.getAtlas().addTexture(fontTexture, TextureAtlasManager.DIFFUSE);

        initFileRepository();
        initPlayerStateRepository();

        PlayerStateDTO playerStateDTO = initPlayer();

        // Block world setup depends on the player position
        initBlockFramework(playerStateDTO);
        initInputManager();
        font = initGui(font);

        camHeight = cam.getHeight();
        camWidth = cam.getWidth();

        stateManager.attachAll(
                new SunState(),
                new MoonState(),
                new SkyState(),
                new TimeFactorState()
        );

        StatsAppState statsAppState = new StatsAppState();
        if (DEV_MODE) {
            atlasManager.dump();
            statsAppState.setDisplayStatView(true);
            statsAppState.setFont(font);
            stateManager.attachAll(
                    statsAppState,
                    //new DetailedProfilerState(),
                    new IalonDebugState(),
                    new DebugKeysAppState(),
                    new WireframeState()
            );
        } else {
            statsAppState.setDisplayStatView(false);
            stateManager.attach(statsAppState);
        }

        stateManager.getState(SunState.class).setTime(playerStateDTO.getTime());
        stateManager.getState(TimeFactorState.class).setTimeFactorIndex(playerStateDTO.getTimeFactorIndex());
    }

    private BitmapFont initGui(BitmapFont font) {
        stateManager.getState(BasePickState.class).removeCollisionRoot(rootNode);

        // Reload the font using the offset of the atlas tile
        AssetInfo assetInfo = assetManager.locateAsset(new AssetKey<>("Textures/font-default.fnt"));
        BitmapFont atlasFont;
        try {
            atlasFont = BitmapFontLoader.mapAtlasFont(assetInfo, font, atlasManager.getAtlas());
        } catch (IOException e) {
            log.error("Failed to load atlas font", e);
            return null;
        }

        // Use the atlas texture
        Texture tex = atlasManager.getDiffuseMap();
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        Material fontMaterial = atlasFont.getPage(0);
        fontMaterial.getTextureParam("ColorMap").setTextureValue(tex);
        fontMaterial.setColor("Color", ColorRGBA.White);

        // Set the remapped font int the default style
        Styles styles = GuiGlobals.getInstance().getStyles();
        styles.setDefault(atlasFont);
        return atlasFont;
    }

    private void initFileRepository() {
        fileRepository = new ZipFileRepository();
        fileRepository.setPath(filepath);
    }

    private void initPlayerStateRepository() {
        playerStateRepository = new PlayerStateRepository();
        playerStateRepository.setPath(filepath);
    }

    private PlayerStateDTO initPlayer() {
        playerState = new PlayerState();
        PlayerStateDTO playerStateDTO = playerStateRepository.load();
        if (playerStateDTO != null) {
            if (playerStateDTO.getLocation() != null) {
                playerState.setPlayerLocation(playerStateDTO.getLocation());
            }
            if (playerStateDTO.getRotation() != null && playerStateDTO.getRotation().getRotationColumn(2).isUnitVector()) {
                cam.setRotation(playerStateDTO.getRotation());
            }
            playerState.setFly(playerStateDTO.isFly());

        } else {
            playerStateDTO = new PlayerStateDTO();
        }

        playerState.setEnabled(false);
        stateManager.attach(playerState);
        return playerStateDTO;
    }

    private void initBlockFramework(PlayerStateDTO playerStateDTO) {
        configureBlocksFramework(assetManager, atlasManager);

        log.info("{} blocks registered", BlocksConfig.getInstance().getBlockRegistry().size());

        terrainGenerator = new NoiseTerrainGenerator(2);
        chunkManager = ChunkManager.builder()
                .poolSize(CHUNK_POOLSIZE)
                .generator(terrainGenerator)
                .repository(fileRepository)
                .build();

        chunkNode = new Node("chunk-node");
        rootNode.attachChild(chunkNode);

        ChunkPager chunkPager = new ChunkPager(chunkNode, chunkManager);
        chunkPager.setLocation(playerState.getPlayerLocation());
        chunkPager.setGridLowerBounds(GRID_LOWER_BOUND);
        chunkPager.setGridUpperBounds(GRID_UPPER_BOUND);
        chunkPager.setMaxUpdatePerFrame(100);

        BulletAppState bulletAppState = new BulletAppState();
        bulletAppState.setDebugEnabled(DEBUG_COLLISIONS);
        stateManager.attach(bulletAppState);

        PhysicsSpace physicsSpace = bulletAppState.getPhysicsSpace();
        PhysicsChunkPager physicsChunkPager = new PhysicsChunkPager(physicsSpace, chunkManager);
        physicsChunkPager.setLocation(playerState.getPlayerLocation());
        physicsChunkPager.setGridLowerBounds(GRID_LOWER_BOUND);
        physicsChunkPager.setGridUpperBounds(GRID_UPPER_BOUND);

        ChunkManagerState chunkManagerState = new ChunkManagerState(chunkManager);
        ChunkPagerState chunkPagerState = new ChunkPagerState(chunkPager);
        PhysicsChunkPagerState physicsChunkPagerState = new PhysicsChunkPagerState(physicsChunkPager);

        ChunkLiquidManagerState chunkLiquidManagerState = new ChunkLiquidManagerState();
        chunkLiquidManagerState.setEnabled(SIMULATE_LIQUID_FLOW);
        BlockSelectionState blockSelectionState = new BlockSelectionState();
        GridSettingsState gridSettingsState = new GridSettingsState();
        gridSettingsState.setRadius(playerStateDTO.getGridRadius());

        stateManager.attachAll(
                chunkManagerState,
                chunkPagerState,
                physicsChunkPagerState,
                chunkLiquidManagerState,
                blockSelectionState,
                gridSettingsState
        );
    }

    public static void configureBlocksFramework(AssetManager assetManager, TextureAtlasManager atlasManager) {
        BlocksConfig.initialize(assetManager, false);
        BlocksConfig config = BlocksConfig.getInstance();
        config.setGrid(new Vec3i(GRID_SIZE, GRID_HEIGHT * 2 + 1, GRID_SIZE));
        config.setPhysicsGrid(new Vec3i(PHYSICS_GRID_SIZE, PHYSICS_GRID_SIZE, PHYSICS_GRID_SIZE));
        config.setChunkSize(new Vec3i(CHUNK_SIZE, CHUNK_HEIGHT, CHUNK_SIZE));
        config.getShapeRegistry().registerDefaultShapes();
        config.setChunkMeshGenerator(new FacesMeshGenerator());

        TypeRegistry typeRegistry = config.getTypeRegistry();
        typeRegistry.setTheme(new BlocksTheme("Ialon", "/ialon-theme"));
        typeRegistry.setAtlasRepository(atlasManager);
        typeRegistry.registerDefaultMaterials();

        registerIalonBlocks();
    }

    public static void registerIalonBlocks() {
        for (IalonBlock block : IalonBlock.values() ) {
            Material material = BlocksConfig.getInstance().getTypeRegistry().get(block.getType());
            if (material == null) {
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

    private void initInputManager() {
        inputManager.addMapping(ACTION_SWITCH_MOUSELOCK, new KeyTrigger(KeyInput.KEY_BACK));
        inputManager.addMapping(ACTION_TOGGLE_TIME_RUN, new KeyTrigger(KeyInput.KEY_P));
        inputManager.addMapping(ACTION_TOGGLE_FULLSCREEN, new KeyTrigger(KeyInput.KEY_F2));
        inputManager.addListener(this, ACTION_SWITCH_MOUSELOCK, ACTION_TOGGLE_TIME_RUN, ACTION_TOGGLE_FULLSCREEN);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (!playerState.isEnabled()) {
            startPlayer();
        }
        if (checkResize && (cam.getWidth() != camWidth || cam.getHeight() != camHeight)) {
            stateManager.getState(TimeFactorState.class).resize();
            stateManager.getState(GridSettingsState.class).resize();
            stateManager.getState(BlockSelectionState.class).resize();
            stateManager.getState(PlayerState.class).resize();

            checkResize = false;
            camWidth = cam.getWidth();
            camHeight = cam.getHeight();
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_SWITCH_MOUSELOCK.equals(name) && isPressed) {
            log.info("Switching mouse lock to {}", !mouselocked);
            switchMouseLock();

        } else if (ACTION_TOGGLE_TIME_RUN.equals(name) && isPressed) {
            log.info("Toggle time run");
            getStateManager().getState(SunState.class).getSunControl().toggleTimeRun();

        } else if (ACTION_TOGGLE_FULLSCREEN.equals(name) && isPressed) {
            log.info("Toggle fullscreen");
            if (this.settings.isFullscreen()) {
                settings.setResolution(Config.SCREEN_WIDTH, Config.SCREEN_HEIGHT);
                settings.setFullscreen(false);
            } else {
                settings.setResolution(-1, -1);
                settings.setFullscreen(true);
            }
            this.restart();
        }
    }

    public void startPlayer() {
        int gridSize = getStateManager().getState(GridSettingsState.class).getRadius() * 2 + 1;
        int total = gridSize * gridSize * GRID_HEIGHT;

        ChunkPager chunkPager = getStateManager().getState(ChunkPagerState.class).getChunkPager();
        PhysicsChunkPager physicsChunkPager = getStateManager().getState(PhysicsChunkPagerState.class).getPhysicsChunkPager();
        int numPagesAttached = chunkPager.getAttachedPages().size();
        int numPhysicPagesAttached = physicsChunkPager.getAttachedPages().size();
        int percent = numPagesAttached * 100 / total;
        if (numPagesAttached > pagesAttached || numPhysicPagesAttached > physicPagesAttached) {
            log.debug("{} pages - {} physic pages attached ({}%)", numPagesAttached, numPhysicPagesAttached, percent);
            pagesAttached = numPagesAttached;
            physicPagesAttached = numPhysicPagesAttached;
        }
        if (numPagesAttached >= total && physicsChunkPager.isReady()) {
            long stopTime = System.currentTimeMillis();
            long duration = stopTime - startTime;
            log.info("World built in {}ms ({}ms per page)", duration, ((float)duration) / pagesAttached);
            log.info("Starting player");
            chunkPager.setMaxUpdatePerFrame(MAX_UPDATE_PER_FRAME);
            physicsChunkPager.setMaxUpdatePerFrame(10);
            playerState.setEnabled(true);
            getStateManager().getState(ChunkLiquidManagerState.class).setEnabled(true);
            getStateManager().getState(SplashscreenState.class).setEnabled(false);
        }
    }

    private void switchMouseLock() {
        mouselocked = !mouselocked;
        GuiGlobals.getInstance().setCursorEventsEnabled(!mouselocked);
        inputManager.setCursorVisible(!mouselocked);
        if (mouselocked) {
            playerState.hideControlButtons();
            stateManager.getState(TimeFactorState.class).setEnabled(false);
            stateManager.getState(GridSettingsState.class).setEnabled(false);
        } else {
            playerState.showControlButtons();
            stateManager.getState(TimeFactorState.class).setEnabled(true);
            stateManager.getState(GridSettingsState.class).setEnabled(true);
        }
    }

    /**
     * Saves (in another thread) the chunk at the given location
     * @param location the location of the chunk
     */
    public void asyncSave(Vec3i location) {
        chunkManager.getChunk(location).ifPresent(chunk -> executorService.submit(() -> {
            try {
                this.getFileRepository().save(chunk);
                log.info("Chunk {} saved", location);
            } catch (Exception e) {
                log.error("Failed to save chunk", e);
            }
        }));
    }

    @Override
    public void start() {
        super.start();
        log.info("Starting Ialon");
    }

    @Override
    public void restart() {
        super.restart();
        checkResize = true;
        log.info("Restarting Ialon");
    }

    @Override
    public void stop() {
        super.stop();
        log.info("Stopping Ialon");
        if (executorService != null) {
            executorService.shutdown();
        }
        if (saveUserPreferencesOnStop) {
            saveUserPreferences();
        }
    }

    public void saveUserPreferences() {
        if (playerStateRepository != null) {
            PlayerStateDTO pstate = new PlayerStateDTO(
                    playerState.getPlayerLocation(),
                    cam.getRotation(),
                    stateManager.getState(SunState.class).getSunControl().getTime());

            pstate.setFly(playerState.isFly());
            pstate.setTimeFactorIndex(stateManager.getState(TimeFactorState.class).getTimeFactorIndex());
            pstate.setGridRadius(stateManager.getState(GridSettingsState.class).getRadius());

            this.playerStateRepository.save(pstate);
        }
    }

}
