package org.delaunois.ialon;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.app.DebugKeysAppState;
import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.font.BitmapFont;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Cylinder;
import com.jme3.scene.shape.Quad;
import com.jme3.shader.VarType;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;
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

import org.delaunois.ialon.control.FollowCamControl;
import org.delaunois.ialon.control.MoonControl;
import org.delaunois.ialon.control.SkyControl;
import org.delaunois.ialon.control.SunControl;
import org.delaunois.ialon.serialize.PlayerStateDTO;
import org.delaunois.ialon.serialize.PlayerStateRepository;
import org.delaunois.ialon.state.BlockSelectionState;
import org.delaunois.ialon.state.ChunkLiquidManagerState;
import org.delaunois.ialon.state.ChunkManagerState;
import org.delaunois.ialon.state.ChunkPagerState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.PhysicsChunkPagerState;
import org.delaunois.ialon.state.PlayerState;
import org.delaunois.ialon.state.StatsAppState;
import org.delaunois.ialon.state.TimeFactorState;
import org.delaunois.ialon.state.WireframeState;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.Getter;
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
import static org.delaunois.ialon.Config.SKY_COLOR;
import static org.delaunois.ialon.Config.SKY_HORIZON_COLOR;
import static org.delaunois.ialon.Config.SKY_ZENITH_COLOR;

/**
 * @author Cedric de Launois
 *
 * TODO :
 * - éliminer l'eau si suppression de la source
 * - rails
 * - bouton flying
 * - plante sous eau
 */
@Slf4j
public class Ialon extends SimpleApplication implements ActionListener {

    private static final String SAVEDIR = "./save";
    private static Path FILEPATH = FileSystems.getDefault().getPath(SAVEDIR);

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
    private SunControl sunControl;

    private PlayerState playerState;
    private boolean checkResize = false;
    private int camHeight = 0;
    private int camWidth = 0;
    private int pagesAttached = 0;
    private int physicPagesAttached = 0;
    private final long startTime = System.currentTimeMillis();
    private ExecutorService executorService;

    public static void main(String[] args) {
        LogAdapter.initialize();
    }

    public static void setFilePath(Path path) {
        FILEPATH = path;
    }

    public Ialon() {
        super(new LightingState());
    }

    @Override
    public void simpleInitApp() {
        log.info("Initializing Ialon");

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
        initBlockFramework();
        SunControl sunControl = initSun(playerStateDTO.getTime());
        initMoon(sunControl);
        initSky(sunControl);
        initInputManager();
        font = initGui(font);

        cam.setFrustumNear(0.1f);
        cam.setFrustumFar(400f);
        cam.setFov(50);
        camHeight = cam.getHeight();
        camWidth = cam.getWidth();

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
    }

    private BitmapFont initGui(BitmapFont font) {
        GuiGlobals.initialize(this);
        stateManager.getState(BasePickState.class).removeCollisionRoot(rootNode);

        // Reload the font using the offset of the atlas tile
        AssetInfo assetInfo = assetManager.locateAsset(new AssetKey<BitmapFont>("Textures/font-default.fnt"));
        BitmapFont atlasFont;
        try {
            atlasFont = BitmapFontLoader.mapAtlasFont(assetInfo, font, atlasManager.getAtlas());
        } catch (IOException e) {
            e.printStackTrace();
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

    private void initSky(SunControl sun) {
        Cylinder skyCylinder = new Cylinder(2, 8, 25f, 20f, true, true);
        FloatBuffer fpb = BufferUtils.createFloatBuffer(38 * 4);
        fpb.put(new float[] {
                // Sides Top Vertices
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,

                // Side Bottom Vertices
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,

                // Top Cap Vertices
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,
                SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a,

                // Bottom Cap Vertices
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a,

                // Top Center Vextex
                SKY_ZENITH_COLOR.r, SKY_ZENITH_COLOR.g, SKY_ZENITH_COLOR.b, SKY_ZENITH_COLOR.a,

                // Bottom center Vertex
                SKY_HORIZON_COLOR.r, SKY_HORIZON_COLOR.g, SKY_HORIZON_COLOR.b, SKY_HORIZON_COLOR.a
        });
        skyCylinder.setBuffer(VertexBuffer.Type.Color, 4, fpb);
        Geometry sky = new Geometry("sky", skyCylinder);

        Quaternion pitch90 = new Quaternion();
        pitch90.fromAngleAxis(FastMath.HALF_PI, new Vector3f(1, 0, 0));
        sky.setLocalRotation(pitch90);

        sky.setQueueBucket(RenderQueue.Bucket.Sky);
        sky.setCullHint(Spatial.CullHint.Never);
        sky.setShadowMode(RenderQueue.ShadowMode.Off);

        Material skyMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        skyMat.setParam("VertexColor", VarType.Boolean, true );
        sky.setMaterial(skyMat);

        SkyControl skyControl = new SkyControl(sun);
        FollowCamControl followCamControl = new FollowCamControl(cam);
        sky.addControl(skyControl);
        sky.addControl(followCamControl);

        Ground groundPlate = new Ground(20, 20);
        Geometry ground = new Geometry("ground", groundPlate);
        ground.setQueueBucket(RenderQueue.Bucket.Sky);
        ground.setCullHint(Spatial.CullHint.Never);
        ground.setShadowMode(RenderQueue.ShadowMode.Off);

        Texture groundTexture = assetManager.loadTexture("Textures/ground.png");
        Material groundMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        groundMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        groundMat.setTexture("ColorMap", groundTexture);
        groundMat.setColor("Color", skyControl.getGroundColor());
        ground.setMaterial(groundMat);
        atlasManager.getAtlas().applyCoords(ground, 0.1f);
        groundMat.setTexture("ColorMap", atlasManager.getDiffuseMap());


        ground.addControl(new FollowCamControl(cam));

        rootNode.attachChild(ground);
        rootNode.attachChildAt(sky, 0);
    }

    private SunControl initSun(float time) {
        Geometry sun = new Geometry("Sun", new Quad(30f, 30f));
        sun.setQueueBucket(RenderQueue.Bucket.Sky);
        sun.setCullHint(Spatial.CullHint.Never);
        sun.setShadowMode(RenderQueue.ShadowMode.Off);

        Texture sunTexture = assetManager.loadTexture("Textures/sun.png");
        Material sunMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        sunMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        sunMat.setTexture("ColorMap", sunTexture);
        //sunMat.setParam("VertexColor", VarType.Boolean, true );
        sunMat.setColor("Color", ColorRGBA.White);
        sun.setMaterial(sunMat);

        atlasManager.getAtlas().applyCoords(sun, 0.1f);
        sunMat.setTexture("ColorMap", atlasManager.getDiffuseMap());

        sunControl = new SunControl(cam);
        sunControl.setDirectionalLight(stateManager.getState(LightingState.class).getDirectionalLight());
        sunControl.setAmbientLight(stateManager.getState(LightingState.class).getAmbientLight());
        sunControl.setTime(time);
        sun.addControl(sunControl);

        rootNode.attachChild(sun);

        return sunControl;
    }

    private void initMoon(SunControl sun) {
        Geometry moon = new Geometry("moon", new Quad(15f, 15f));
        moon.setQueueBucket(RenderQueue.Bucket.Sky);
        moon.setCullHint(Spatial.CullHint.Never);
        moon.setShadowMode(RenderQueue.ShadowMode.Off);

        Texture moonTexture = assetManager.loadTexture("Textures/moon.png");
        Material moonMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        moonMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        moonMat.setTexture("ColorMap", moonTexture);
        moonMat.setColor("Color", ColorRGBA.White);
        moon.setMaterial(moonMat);

        atlasManager.getAtlas().applyCoords(moon, 0.1f);
        moonMat.setTexture("ColorMap", atlasManager.getDiffuseMap());

        MoonControl moonControl = new MoonControl();
        moonControl.setCam(cam);
        moonControl.setSun(sun);
        moon.addControl(moonControl);

        rootNode.attachChild(moon);
    }

    private void initFileRepository() {
        fileRepository = new ZipFileRepository();
        fileRepository.setPath(FILEPATH);
    }

    private void initPlayerStateRepository() {
        playerStateRepository = new PlayerStateRepository();
        playerStateRepository.setPath(FILEPATH);
    }

    private PlayerStateDTO initPlayer() {
        playerState = new PlayerState();
        PlayerStateDTO playerStateDTO = playerStateRepository.load();
        if (playerStateDTO != null) {
            if (playerStateDTO.getLocation() != null) {
                playerState.setPlayerLocation(playerStateDTO.getLocation());
            }
            if (playerStateDTO.getRotation() != null) {
                cam.setRotation(playerStateDTO.getRotation());
            }
        } else {
            playerStateDTO = new PlayerStateDTO();
        }

        playerState.setEnabled(false);
        stateManager.attach(playerState);
        return playerStateDTO;
    }

    private void initBlockFramework() {
        ChunkManagerState chunkManagerState;
        ChunkPagerState chunkPagerState;
        PhysicsChunkPagerState physicsChunkPagerState;

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

        log.info("{} blocks registered", BlocksConfig.getInstance().getBlockRegistry().size());

        //terrainGenerator = new FlatTerrainGenerator(60, BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.GRASS));
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

        chunkManagerState = new ChunkManagerState(chunkManager);
        chunkPagerState = new ChunkPagerState(chunkPager);
        physicsChunkPagerState = new PhysicsChunkPagerState(physicsChunkPager);
        ChunkLiquidManagerState chunkLiquidManagerState = new ChunkLiquidManagerState();
        chunkLiquidManagerState.setEnabled(SIMULATE_LIQUID_FLOW);
        BlockSelectionState blockSelectionState = new BlockSelectionState();
        TimeFactorState timeFactorState = new TimeFactorState();

        stateManager.attachAll(
                chunkManagerState,
                chunkPagerState,
                physicsChunkPagerState,
                chunkLiquidManagerState,
                blockSelectionState,
                timeFactorState
        );
    }

    private void registerIalonBlocks() {
        BlockRegistry registry = BlocksConfig.getInstance().getBlockRegistry();
        for (IalonBlock block : IalonBlock.values() ) {
            Material material = BlocksConfig.getInstance().getTypeRegistry().get(block.getType());
            if (material == null) {
                BlocksConfig.getInstance().getTypeRegistry().register(block.getType());
            }

            for (String shape : block.getShapes()) {
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
                    for (int waterLevel : block.getWaterLevels()) {
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
    }

    private void initInputManager() {
        inputManager.addMapping("switch-mouselock", new KeyTrigger(KeyInput.KEY_BACK));
        inputManager.addMapping("toggle-time-run", new KeyTrigger(KeyInput.KEY_P));
        inputManager.addMapping("toggle-fullscreen", new KeyTrigger(KeyInput.KEY_F2));
        inputManager.addListener(this, "switch-mouselock", "toggle-time-run", "toggle-fullscreen");
    }

    /**
     * This is the main event loop--walking happens here.
     * We check in which direction the player is walking by interpreting
     * the camera direction forward (camDir) and to the side (camLeft).
     * The setWalkDirection() command is what lets a physics-controlled player walk.
     * We also make sure here that the camera moves with player.
     */
    @Override
    public void simpleUpdate(float tpf) {
        if (!playerState.isEnabled()) {
            startPlayer();
        }
        if (checkResize) {
            if (cam.getWidth() != camWidth || cam.getHeight() != camHeight) {
                stateManager.getState(TimeFactorState.class).resize();
                stateManager.getState(BlockSelectionState.class).resize();
                stateManager.getState(PlayerState.class).resize();

                checkResize = false;
                camWidth = cam.getWidth();
                camHeight = cam.getHeight();
            }
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if ("switch-mouselock".equals(name) && isPressed) {
            log.info("Switching mouse lock to {}", !mouselocked);
            switchMouseLock();
        } else if ("toggle-time-run".equals(name) && isPressed) {
            log.info("Toggle time run");
            sunControl.toggleTimeRun();
        } else if ("toggle-fullscreen".equals(name) && isPressed) {
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
        ChunkPager chunkPager = getStateManager().getState(ChunkPagerState.class).getChunkPager();
        PhysicsChunkPager physicsChunkPager = getStateManager().getState(PhysicsChunkPagerState.class).getPhysicsChunkPager();
        int numPagesAttached = chunkPager.getAttachedPages().size();
        int numPhysicPagesAttached = physicsChunkPager.getAttachedPages().size();
        if (numPagesAttached > pagesAttached || numPhysicPagesAttached > physicPagesAttached) {
            log.info("{} pages - {} physic pages attached", numPagesAttached, numPhysicPagesAttached);
            pagesAttached = numPagesAttached;
            physicPagesAttached = numPhysicPagesAttached;
        }
        if (numPagesAttached >= GRID_SIZE * GRID_SIZE * GRID_HEIGHT && physicsChunkPager.isReady()) {
            long stopTime = System.currentTimeMillis();
            long duration = stopTime - startTime;
            log.info("World built in {}ms ({}ms per page)", duration, ((float)duration) / pagesAttached);
            log.info("Starting player");
            chunkPager.setMaxUpdatePerFrame(MAX_UPDATE_PER_FRAME);
            physicsChunkPager.setMaxUpdatePerFrame(10);
            playerState.setEnabled(true);
            getStateManager().getState(ChunkLiquidManagerState.class).setEnabled(true);
        }
    }

    public LocalTime getLocalTime() {
        return sunControl == null ? LocalTime.MIDNIGHT : sunControl.getLocalTime();
    }

    private void switchMouseLock() {
        mouselocked = !mouselocked;
        GuiGlobals.getInstance().setCursorEventsEnabled(!mouselocked);
        inputManager.setCursorVisible(!mouselocked);
        if (mouselocked) {
            playerState.hideControlButtons();
            stateManager.getState(TimeFactorState.class).setEnabled(false);
        } else {
            playerState.showControlButtons();
            stateManager.getState(TimeFactorState.class).setEnabled(true);
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
        if (playerStateRepository != null) {
            this.playerStateRepository.save(
                    new PlayerStateDTO(
                            playerState.getPlayerLocation(),
                            cam.getRotation(),
                            sunControl.getTime()));
        }
    }

}
