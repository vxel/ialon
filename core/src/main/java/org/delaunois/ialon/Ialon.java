package org.delaunois.ialon;

import com.jme3.app.SimpleApplication;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import com.jme3.shader.VarType;
import com.jme3.texture.Texture;
import com.jme3.util.SkyFactory;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.BlocksTheme;
import com.rvandoosselaer.blocks.TypeRegistry;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.mathd.Vec3i;
import com.simsilica.util.LogAdapter;

import org.delaunois.ialon.state.BlockSelectionState;
import org.delaunois.ialon.state.ChunkLiquidManagerState;
import org.delaunois.ialon.state.ChunkManagerState;
import org.delaunois.ialon.state.ChunkPagerState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.PhysicsChunkPagerState;
import org.delaunois.ialon.state.PlayerState;
import org.delaunois.ialon.state.StatsAppState;
import org.delaunois.ialon.state.WireframeState;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.LocalTime;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.Config.CHUNK_HEIGHT;
import static org.delaunois.ialon.Config.CHUNK_SIZE;
import static org.delaunois.ialon.Config.DEBUG_COLLISIONS;
import static org.delaunois.ialon.Config.GRID_HEIGHT;
import static org.delaunois.ialon.Config.GRID_LOWER_BOUND;
import static org.delaunois.ialon.Config.GRID_SIZE;
import static org.delaunois.ialon.Config.GRID_UPPER_BOUND;
import static org.delaunois.ialon.Config.MAX_UPDATE_PER_FRAME;
import static org.delaunois.ialon.Config.PHYSICS_GRID_SIZE;
import static org.delaunois.ialon.Config.PLAYER_START_HEIGHT;

/**
 * @author Cedric de Launois
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
    private TextureAtlasManager atlasManager;

    @Getter
    private boolean mouselocked = false;

    private SunControl sunControl;
    private PlayerState playerState;
    private int pagesAttached = 0;
    private final long startTime = System.currentTimeMillis();

    public static void main(String[] args) {
        LogAdapter.initialize();
    }

    public static void setFilePath(Path path) {
        FILEPATH = path;
    }

    public Ialon() {
        super(
                new StatsAppState(),
                new IalonDebugState(),
                new BlockSelectionState(),
                //new DebugKeysAppState(),
                new WireframeState(),
                new LightingState()
                //new ExplorerDebugState(),
                //new ShadowProcessingState()
        );
    }

    @Override
    public void simpleInitApp() {
        GuiGlobals.initialize(this);
        RenderQueue rq = viewPort.getQueue();

        atlasManager = new TextureAtlasManager();
        viewPort.setBackgroundColor(new ColorRGBA(0.5f, 0.6f, 0.7f, 1.0f));
        rq.setGeometryComparator(RenderQueue.Bucket.Transparent,
                new LayerComparator(rq.getGeometryComparator(RenderQueue.Bucket.Transparent), -1));
        cam.setFrustumNear(0.1f);
        cam.setFrustumFar(200f);
        cam.setFov(50);

        initSky();
        initFileRepository();
        initBlockFramework();
        initSun();
        initInputManager();
    }

    private void initSky() {
        Texture up = getAssetManager().loadTexture("Textures/Sky/800px/TropicalSunnyDay_py.jpg");
        Texture down = getAssetManager().loadTexture("Textures/Sky/800px/TropicalSunnyDay_ny.jpg");
        Texture north = getAssetManager().loadTexture("Textures/Sky/800px/TropicalSunnyDay_nz.jpg");
        Texture south = getAssetManager().loadTexture("Textures/Sky/800px/TropicalSunnyDay_pz.jpg");
        Texture east = getAssetManager().loadTexture("Textures/Sky/800px/TropicalSunnyDay_nx.jpg");
        Texture west = getAssetManager().loadTexture("Textures/Sky/800px/TropicalSunnyDay_px.jpg");
        rootNode.attachChild(SkyFactory.createSky(getAssetManager(), west, east, north, south, up, down));
    }

    private void initSun() {
        Geometry sun = new Geometry("Sun", new Quad(15f, 15f));
        sun.setQueueBucket(RenderQueue.Bucket.Sky);
        sun.setCullHint(Spatial.CullHint.Never);
        sun.setShadowMode(RenderQueue.ShadowMode.Off);

        Texture texture = assetManager.loadTexture("Textures/sun.png");
        atlasManager.getAtlas().addTexture(texture, TextureAtlasManager.DIFFUSE);

        Material sunMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        sunMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        sunMat.setTexture("ColorMap", texture);
        sunMat.setParam("VertexColor", VarType.Boolean, true );
        sun.setMaterial(sunMat);
        atlasManager.getAtlas().applyCoords(sun);
        sunMat.setTexture("ColorMap", atlasManager.getDiffuseMap());
        sunMat.getTextureParam("ColorMap").getTextureValue().setMinFilter(Texture.MinFilter.NearestLinearMipMap);

        sunControl = new SunControl();
        sunControl.setCam(cam);
        sunControl.setDirectionalLight(stateManager.getState(LightingState.class).getDirectionalLight());
        sunControl.setAmbientLight(stateManager.getState(LightingState.class).getAmbientLight());
        sun.addControl(sunControl);

        rootNode.attachChild(sun);
    }

    private void initFileRepository() {
        fileRepository = new ZipFileRepository();
        fileRepository.setPath(FILEPATH);
    }

    private void initBlockFramework() {
        ChunkManagerState chunkManagerState;
        ChunkPagerState chunkPagerState;
        PhysicsChunkPagerState physicsChunkPagerState;

        BlocksConfig.initialize(assetManager, false);
        BlocksConfig.getInstance().setGrid(new Vec3i(GRID_SIZE, GRID_HEIGHT * 2 + 1, GRID_SIZE));
        BlocksConfig.getInstance().setPhysicsGrid(new Vec3i(PHYSICS_GRID_SIZE, PHYSICS_GRID_SIZE, PHYSICS_GRID_SIZE));
        BlocksConfig.getInstance().setChunkSize(new Vec3i(CHUNK_SIZE, CHUNK_HEIGHT, CHUNK_SIZE));
        BlocksConfig.getInstance().getShapeRegistry().registerDefaultShapes();
        BlocksConfig.getInstance().getBlockRegistry().registerDefaultBlocks();

        TypeRegistry typeRegistry = BlocksConfig.getInstance().getTypeRegistry();
        typeRegistry.setTheme(new BlocksTheme("Ialon", "/ialon-theme"));
        typeRegistry.setAtlasRepository(atlasManager);
        typeRegistry.registerDefaultMaterials();

        //terrainGenerator = new FlatTerrainGenerator(60, BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.GRASS));
        terrainGenerator = new NoiseTerrainGenerator(2);
        chunkManager = ChunkManager.builder()
                .poolSize(8)
                .generator(terrainGenerator)
                .repository(fileRepository)
                .build();

        Vector3f location = new Vector3f(CHUNK_SIZE / 2f, terrainGenerator.getHeight(new Vector3f(0, 0, 0)) + PLAYER_START_HEIGHT, CHUNK_SIZE / 2f);

        chunkNode = new Node("chunk-node");
        rootNode.attachChild(chunkNode);

        ChunkPager chunkPager = new ChunkPager(chunkNode, chunkManager);
        chunkPager.setLocation(location);
        chunkPager.setGridLowerBounds(GRID_LOWER_BOUND);
        chunkPager.setGridUpperBounds(GRID_UPPER_BOUND);
        chunkPager.setMaxUpdatePerFrame(100);

        BulletAppState bulletAppState = new BulletAppState();
        bulletAppState.setDebugEnabled(DEBUG_COLLISIONS);
        stateManager.attach(bulletAppState);

        PhysicsSpace physicsSpace = bulletAppState.getPhysicsSpace();
        PhysicsChunkPager physicsChunkPager = new PhysicsChunkPager(physicsSpace, chunkManager);
        physicsChunkPager.setLocation(location);
        physicsChunkPager.setGridLowerBounds(GRID_LOWER_BOUND);
        physicsChunkPager.setGridUpperBounds(GRID_UPPER_BOUND);

        chunkManagerState = new ChunkManagerState(chunkManager);
        chunkPagerState = new ChunkPagerState(chunkPager);
        physicsChunkPagerState = new PhysicsChunkPagerState(physicsChunkPager);
        ChunkLiquidManagerState chunkLiquidManagerState = new ChunkLiquidManagerState();

        playerState = new PlayerState();
        playerState.setEnabled(false);

        stateManager.attachAll(
                chunkManagerState,
                chunkPagerState,
                physicsChunkPagerState,
                chunkLiquidManagerState,
                playerState
        );
    }

    private void initInputManager() {
        inputManager.addMapping("switch-mouselock", new KeyTrigger(KeyInput.KEY_BACK));
        inputManager.addMapping("toggle-time-run", new KeyTrigger(KeyInput.KEY_P));
        inputManager.addListener(this, "switch-mouselock", "toggle-time-run");
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
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if ("switch-mouselock".equals(name) && isPressed) {
            log.info("Switching mouse lock to {}", !mouselocked);
            switchMouseLock();
        } else if ("toggle-time-run".equals(name) && isPressed) {
            log.info("Toggle time run");
            sunControl.toggleTimeRun();
        }
    }

    public void startPlayer() {
        ChunkPager chunkPager = getStateManager().getState(ChunkPagerState.class).getChunkPager();
        PhysicsChunkPager physicsChunkPager = getStateManager().getState(PhysicsChunkPagerState.class).getPhysicsChunkPager();
        int size = chunkPager.getAttachedPages().size();
        if (size > pagesAttached) {
            log.info("{} pages attached", size);
            pagesAttached = size;
        }
        if (size >= GRID_SIZE * GRID_SIZE * GRID_HEIGHT && physicsChunkPager.isIdle()) {
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
        } else {
            playerState.showControlButtons();
        }
    }

}
