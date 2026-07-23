package org.delaunois.ialon;

import com.jme3.font.BitmapFont;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.CameraNode;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ChunkRepository;
import org.delaunois.ialon.blocks.TextureAtlasManager;
import org.delaunois.ialon.blocks.WorldEditOverlay;
import org.delaunois.ialon.blocks.WorldSettings;
import org.delaunois.ialon.blocks.ZipFileRepository;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.blocks.generator.TerrainGenerator;
import org.delaunois.ialon.input.InputActionManager;

import java.nio.file.FileSystems;
import java.nio.file.Path;

import lombok.Getter;
import lombok.Setter;

/**
 * Global configuration options for Ialon
 */
@Getter
@Setter
public class IalonConfig implements WorldSettings {

    public static final String SAVEDIR = "./save";
    public static final String CHUNK_NODE_NAME = "chunk-node";
    // UI font, baked from a TTF by the support BitmapFontGenerator tool (./gradlew :core:generateFont).
    // Single source of truth : it is packed into the texture atlas and installed as Lemur's default font
    // by IalonInitializer, so every UI element follows this one path.
    public static final String FONT_PATH = "Textures/font-jura.fnt";
    public static final int FPS_LIMIT_MOBILE = 120;
    public static final int FPS_LIMIT_DESKTOP = 120;

    // Screen - Rendering
    // True when the hardware sRGB framebuffer is unavailable (e.g. Android GLES, where
    // setGammaCorrection(true) is a silent no-op) : our world shaders then emulate the sRGB pipeline
    // (texture decode + output encode) so colours match the desktop hardware path. Set at startup
    // from the renderer caps (Ialon.simpleInitApp) ; not persisted.
    private boolean manualGammaEncode = false;
    private int screenWidth = 1520;
    private int screenHeight = 720;
    // Desktop windowing mode. Defaults to fullscreen ; toggled at runtime with F2 (ScreenState) and
    // persisted in game.yml so the last-used mode is restored on the next launch (DesktopLauncher).
    // Ignored on Android (always fullscreen).
    private boolean fullscreen = true;
    /**
     * Chunk pages the pager may attach to (or detach from) the scene graph in a single frame. Counts only
     * pages that really enter the graph — air pages are free and excluded (ChunkPager#attachNextPages).
     *
     * <p>Sized against what a chunk crossing actually delivers : at render distance 8 the player crossing
     * one chunk boundary reveals a slab of {@code gridSize * gridHeight} = 170 chunks, of which ~40 carry
     * geometry. A budget of 8 spread those over 5+ frames at best, and once the queue stopped draining fast
     * enough the pager re-requested the meshes it was still waiting on, which snowballed :
     * {@code ChunkPagingBenchmark} measured 92% of all meshing wasted and the grid never filling. Meshing
     * throughput was never the limit — the phone generates and meshes a slab in a fraction of a crossing.
     */
    private int maxUpdatePerFrame = 32;
    private int chunkPoolsize = defaultChunkPoolSize();

    /**
     * Side, in chunks, of the regions the opaque terrain mesh is merged into (see
     * {@link org.delaunois.ialon.state.ChunkRegionBatchState}). 1 disables merging.
     *
     * <p>The frame is bound by per-draw CPU, not by geometry — measured : a quarter of the pixels left the
     * opaque pass unchanged (ratio 1.01x), the render thread's CPU matched its wall time, and 122 chunk draws
     * under one material cost ~45-56 us each. Merging trades draws for culling granularity, and 2 is the
     * conservative setting : ~34 draws instead of 122 while keeping cave/frustum culling nearly as tight.
     * Raising it removes more draws and culls more coarsely — measure on device rather than assume, the
     * balance depends on how much geometry the coarser regions drag in.
     */
    private int chunkBatchRegionSize = 2;

    /** Regions the batcher may rebuild per frame. Regions awaiting rebuild render per chunk meanwhile. */
    private int chunkBatchRegionsPerFrame = 2;

    // Grid
    private int gridRadius = 4;
    private int gridRadiusMin = 0;
    private int gridRadiusMax = 15;
    private int physicsGridRadius = 1;
    private int chunkSize = 16;
    private int chunkHeight = 16;
    private int gridHeight = 10;

    // World

    // Finite world : when true the world is a torus -- the terrain is seamless and periodic in X and
    // Z with period worldSize (= worldSizeChunks * chunkSize world units), so the -x/+x and -z/+z
    // edges join perfectly. The player roams freely (no runtime bound) but its saved position is wrapped
    // back into the origin-centered tile on load (see wrapToWorld), and saves/edits are keyed modulo
    // worldSizeChunks so content is finite and consistent across the seam. When false the world is the
    // legacy infinite, non-tiling terrain.
    private boolean finiteWorld = true;
    private int worldSizeChunks = 256; // square world side, in chunks (256 * 16 = 4096 world units)

    // Identity and generation parameters of the currently loaded world. Persisted per-world in
    // worlds/<worldId>/world.yml (see WorldParams) ; the defaults below reproduce the original single
    // world exactly (seed 2 and the NoiseTerrainGenerator constants), so the migrated "default" world is
    // byte-identical. Player-created worlds override these via WorldParams#applyTo before generation.
    public static final String DEFAULT_WORLD_ID = "default";
    public static final String WORLDS_DIR = "worlds";
    private String worldId = DEFAULT_WORLD_ID;
    private String worldName = "World 1";
    private long seed = 2;
    private float reliefAmplitude = 1f; // overall terrain height (mountains/hills) ; 1 = default relief
    private float reliefFrequency = 1f; // density of relief features ; 1 = default
    private float treeDensity = 0.70f; // tree chance per cell in dense woods (NoiseTerrainGenerator default)
    private float forestPatchSize = 1f; // size of woods/clearings ; 1 = default, higher = larger patches

    // Far terrain : a distant low-detail heightmap horizon rendered beyond the loaded chunks.
    private boolean farTerrain = true;
    private float farTerrainFogDistance = 2500f;
    private float farTerrainFogDensity = 3f;
    // Displayed colours are authored in sRGB but stored in LINEAR space (setAsSrgb), because the
    // renderer runs the sRGB pipeline (gamma correction on) : shaders receive/emit linear values and
    // the framebuffer encodes back to sRGB on output. The literal sRGB numbers below are the intended
    // on-screen colours ; setAsSrgb keeps them looking the same after the framebuffer encode. (Light
    // INTENSITIES, e.g. White * ambiantIntensity, stay raw - they are linear multipliers, not colours.)
    private ColorRGBA farTerrainBaseColor = new ColorRGBA().setAsSrgb(0.28f, 0.45f, 0.16f, 1f); // grass / land : mean albedo of the voxel grass texture (Blocks/Textures/grass.png) so near & far land read alike
    private ColorRGBA farTerrainWaterColor = new ColorRGBA().setAsSrgb(0.245f, 0.421f, 0.535f, 0.92f); // teal-blue, matched to the voxel water texture (Blocks/Textures/water_calm.png) so near & far seas read as one body
    private ColorRGBA farTerrainSandColor = new ColorRGBA().setAsSrgb(0.70f, 0.68f, 0.51f, 1f); // mean albedo of the voxel sand texture (Blocks/Textures/sand.png)
    private ColorRGBA farTerrainRockColor = new ColorRGBA().setAsSrgb(0.48f, 0.47f, 0.46f, 1f); // bare rock (high mountains)
    private ColorRGBA farTerrainBedrockColor = new ColorRGBA().setAsSrgb(0.57f, 0.57f, 0.56f, 1f); // grey bedrock on the deep sea FLOOR (mean albedo of Blocks/Textures/rock.png) : brighter/greyer than the muted, fogged mountain-rock colour above, so the distant floor reads the same grey-blue as the near voxel bedrock seen through water (NoiseTerrainGenerator forces ROCK at worldY <= 0)
    private ColorRGBA farTerrainSnowColor = new ColorRGBA().setAsSrgb(0.92f, 0.94f, 0.97f, 1f); // snow caps (highest peaks)
    private ColorRGBA farTerrainSeabedColor = new ColorRGBA().setAsSrgb(0.44f, 0.31f, 0.19f, 1f); // dirt-brown sea FLOOR (mean albedo of Blocks/Textures/dirt.png) : the second far surface shows the real seabed underwater, sand-fringed near the shore then dirt deeper -- like the voxel floor (NoiseTerrainGenerator: sand < 3 below water, then dirt)
    private float farTerrainExtent = 4096f; // world span covered by the far terrain, centered on origin
    private float farTerrainVerticalOffset = 1f; // fine vertical nudge of the far terrain to best line up with the voxel surface at the seam (poke-through/z-fighting are handled by farTerrainDepthBias)
    private float farTerrainDepthBias = 0.1f; // clip-space depth bias : voxels win the depth test over the far terrain (prevents poke-through)
    // The two knobs below both target ONE artifact : on Android (never on desktop) the distant coastline
    // wobbled as the camera moved, and was rock steady at rest. It is a TANGENCY problem, not a precision
    // one -- the relief crosses the flat far water plane at a near-zero angle, so any per-pixel depth error
    // times that slope is a large horizontal displacement of the waterline. Ruled out by measurement, so
    // don't re-derive them: depth-buffer bits (24 granted on device, ~0.007 units of step at 130 units),
    // shader precision (GLSLCompat forces highp), polygon offset (disabled on both surfaces), the clip-space
    // depth bias (added identically to both, order-preserving), and the water plane's coarse triangles
    // (tested at WATER_GRID_SIZE 257, no change). Removing the water plane entirely removes the wobble.
    private float farTerrainWaterlineClearance = 2f; // world units the far relief is kept clear of the water plane, above (land) and below (sea floor) : collapses the grazing band from hundreds of units to a single heightmap cell (16 units) without moving the coastline contour, since each sample keeps its side. Raise to steepen the crossing further ; lower if the coastline reads too angular. 0 disables
    private float farWaterShoreMargin = 0f; // world units : the far water plane draws itself ONLY where the relief is at least this far BELOW it, cutting its shoreline analytically from the heightmap (Water.frag SHORE_CLIP) instead of leaving the edge to a depth-test tie. Kept LOW deliberately : larger values clip away enough water to expose black artifacts at the coast junction in grazing view, so this trades wobble against those. Raise if the wobble returns, lower if the artifacts show. 0 disables the clip
    private float farTerrainNoiseStrength = 0.6f; // fBm brightness variation on the distant land, to break up the flat altitude palette (0 = flat colour, define compiled out)
    private float farTerrainNoiseScale = 0.08f; // spatial frequency of that variation (world units : ~1/feature-size, here ~25-unit blobs)

    // Far trees : GPU billboards scattered on the far terrain (FarTreeState), matching the voxel woods.
    private boolean farTree = true;
    private float farTreeDistance = 350f; // world radius of the billboard ring around the player (beyond it, only the shader forest tint shows)
    private int farTreeMaxCount = 8000; // hard cap on billboards (the region is thinned to fit, with a log line)
    private float farTreeScale = 1f; // global size multiplier for the tree sprites
    private float farTreeSink = 1f; // sink the billboard base this many world units into the ground so vertical billboards don't appear to float on slopes (their base is a flat line at the anchor's column height, above the downhill terrain)
    // Forest tint : the far terrain darkens to this colour where the forest-density field is high (the
    // distant, beyond-billboard woods read as dark-green slopes). Authored sRGB, stored linear (setAsSrgb).
    private ColorRGBA forestTintColor = new ColorRGBA().setAsSrgb(0.10f, 0.26f, 0.09f, 1f);
    private float forestTintStrength = 0.55f; // how strongly dense woods pull the grass toward forestTintColor

    private float waterHeight = 30;
    private boolean greedyCalmWater = true;
    // Cave culling : hide chunks the camera cannot see through the chunk visibility graph
    // (behind walls, underground). Minecraft-style advanced occlusion culling.
    private boolean chunkCaveCulling = true;
    private ColorRGBA calmWaterColor = new ColorRGBA().setAsSrgb(0.19f, 0.52f, 0.70f, 0.5f); // mean albedo+alpha of Blocks/Textures/water_calm.png (alpha kept as-is by setAsSrgb)
    private float waterOpaqueDistance = 30f; // horizontal distance (world units) over which the water surface fades from its base alpha to fully opaque (progressive opacity with distance, near & far water alike)
    private boolean simulateLiquidFlow = true;
    private int simulateLiquidFlowModel = 2;
    private float waterSimulationSpeed = 4f;
    // Lava flows like water but slower : its own (lower) simulation cadence. See ChunkLiquidManagerState.
    private float lavaSimulationSpeed = 0.5f;

    // Underwater view : a full-screen post-process (UnderwaterState) applied only while the camera is
    // below waterHeight - a bluish distance fog that shortens the view range plus a gentle ripple
    // ("eau qui bouge"). Authored sRGB, stored linear (setAsSrgb), like the other displayed colours.
    private ColorRGBA underwaterFogColor = new ColorRGBA().setAsSrgb(0.10f, 0.30f, 0.42f, 1f); // deep blue-green
    private float underwaterFogDistance = 150;  // distance scale of the fog (smaller = shorter view)
    private float underwaterFogDensity = 1.8f;   // fog thickness
    private float underwaterDistortionAmplitude = 0.002f; // ripple strength (texCoord units)
    private float underwaterDistortionSpeed = 1.0f;       // ripple speed
    private float underwaterDistortionFrequency = 18f;    // ripple spatial frequency
    // Depth (world units, blockScale = 1 => blocks) over which the fog fades from off (eye right at
    // the surface, so you still see out of the water) to full. Avoids the whole view popping to blue
    // the instant the eye dips below the surface.
    private float underwaterFogFadeDepth = 30f;

    // In-lava view : same full-screen post-process as underwater but a dense, near-opaque orange fog
    // (you can barely see when submerged in lava). Authored sRGB, stored linear (setAsSrgb).
    private ColorRGBA lavaFogColor = new ColorRGBA().setAsSrgb(0.85f, 0.28f, 0.06f, 1f); // molten orange
    private float lavaFogDistance = 6f;    // very short view range inside lava
    private float lavaFogDensity = 3.0f;   // thick fog
    private float ambiantIntensity = 0.55f;
    private float sunIntensity = 1.0f;
    private float sunAmplitude = 10f;

    // Sound
    // Master gain, applied to the jME Listener (so it covers every sound, present and future). 0 = mute.
    // The only sound setting exposed in the UI ; everything below is authoring, not player-facing.
    private float soundVolume = 0.7f;

    // Ambient wildlife (AmbientSoundState + AmbienceScheduler). Two banks driven by the same forest
    // density : birds by day, owls by night. The numbers come from the offline audition rendered by
    // local/tools/preview-ambience.sh -- change them there first, they are much faster to judge.
    private boolean ambientSound = true;      // master switch for the wildlife layers
    private float birdVolume = 1.0f;          // per-call gain before the random spread
    private int birdMaxLayers = 3;            // concurrent independent call layers at full forest density
    private float birdMinGapSeconds = 2.5f;   // gap between calls on a layer, deep in a wood
    private float birdMaxGapSeconds = 14f;    // ... and at the density where the layer switches on
    private float birdMinDistance = 8f;       // how close a call may be placed around the listener
    private float birdMaxDistance = 30f;
    private float birdPitchSpread = 0.07f;    // +/- fraction ; makes 7 clips pass for a whole wood
    // Owls : one layer, far apart, louder per call (a single distant hoot would otherwise vanish).
    private float owlVolume = 2.2f;
    private int owlMaxLayers = 1;
    private float owlMinGapSeconds = 15f;
    private float owlMaxGapSeconds = 40f;
    private float owlMinDistance = 20f;
    private float owlMaxDistance = 45f;
    private float owlPitchSpread = 0.04f;     // a hoot tolerates far less transposition than a chirp
    // Ambience beds : continuous, non-positional loops faded in and out. Water is heard while the head
    // is under water (never in lava, which will want its own sound).
    private float waterBedVolume = 0.6f;

    private boolean showFps = true; // on-screen FPS counter (StatsAppState)
    private boolean showPosition = false; // on-screen world position, top-left (StatsAppState)
    private boolean showMinimap = true; // on-screen top-down far-terrain minimap (MinimapState)
    private int maxFramerate = FPS_LIMIT_DESKTOP; // frame rate cap ; toggled between 60 and 120 in the settings (battery on mobile)

    private ColorRGBA skyColor = new ColorRGBA().setAsSrgb(100 / 255f, 172 / 255f, 255 / 255f, 1f);
    private ColorRGBA skyZenithColor = new ColorRGBA().setAsSrgb(14 / 255f, 62 / 255f, 138 / 255f, 1f);
    private ColorRGBA skyHorizonColor = ColorRGBA.White;

    private ColorRGBA skyDayColor = ColorRGBA.White;
    private ColorRGBA skyEveningColor = new ColorRGBA().setAsSrgb(1f, 0.7f, 0.5f, 1);
    private ColorRGBA skyNightColor = new ColorRGBA().setAsSrgb(9 / 255f, 12 / 255f, 19 / 255f, 1f);
    private ColorRGBA groundDayColor = new ColorRGBA().setAsSrgb(141 / 255f, 199 / 255f, 255 / 255f, 1f);
    private ColorRGBA groundEveningColor = new ColorRGBA().setAsSrgb(150 / 255f, 136 / 255f, 126 / 255f, 1f);
    private ColorRGBA groundNightColor = new ColorRGBA().setAsSrgb(6 / 255f, 6 / 255f, 12 / 255f, 1f);
    private ColorRGBA dayColor = ColorRGBA.White;
    private ColorRGBA eveningColor = new ColorRGBA().setAsSrgb(255 / 255f, 173 / 255f, 66 / 255f, 1f);
    private ColorRGBA nightColor = new ColorRGBA().setAsSrgb(0.2f, 0.2f, 0.2f, 1);

    private float time = FastMath.HALF_PI;
    private int timeFactorIndex = 1;
    private float timeFactor = 0.01f; // Should be 0.01f
    private float groundGravity = 9;
    private float waterGravity = 0.4f;
    private float jumpSpeed = 5f;
    private float waterJumpSpeed = 5f;

    // Player
    private Vector3f playerLocation = new Vector3f();
    private CameraNode cameraNode;
    private Quaternion playerRotation = new Quaternion();
    private float playerYaw = 0;
    private float playerPitch = 0;
    private float rotationSpeed = 1.5f;
    private float rotationSpeedRail = 5f;
    private float playerStartHeight = 10;
    private float playerMoveSpeed = 0.05f;
    private float playerFlySpeed = 0.1f;
    private float playerRailSpeed = 0.04f;
    private float playerRailFriction = 0.2f;
    private float playerHeight = 1.6f;
    private float playerRadius = 0.3f;
    private float playerStepHeight = 0.3f;
    private boolean playerStartFly = false;
    private boolean saveUserSettingsOnStop = true;
    private final InputActionManager inputActionManager = new InputActionManager();
    private Path savePath = FileSystems.getDefault().getPath(SAVEDIR);

    private Block selectedBlock = null;
    private int selectedBlockIndex = 0;
    private String selectedBlockName = null;
    private ChunkManager chunkManager;
    private ChunkRepository chunkRepository;
    private TerrainGenerator terrainGenerator;
    // Player edits the far horizon must honour (felled trees / reshaped relief). One instance per config,
    // populated from the world's save by WorldEditOverlayRepository when the world is opened.
    private final WorldEditOverlay worldEditOverlay = new WorldEditOverlay();
    private final TextureAtlasManager textureAtlasManager = new TextureAtlasManager();
    // The block catalog loaded from Blocks/blocks.yaml (single source of truth for block definitions).
    // Set by IalonInitializer.configureBlocksFramework ; read by the block-selection slider.
    private IalonBlockCatalog blockCatalog;
    private BitmapFont font;

    // Debug
    private boolean devMode = false;
    private boolean debugCollisions = false;
    private boolean debugGrid = false;
    private boolean debugChunks = false;

    public void setGridRadius(int gridRadius) {
        this.gridRadius = clamp(gridRadius, gridRadiusMin, gridRadiusMax);
    }

    public void setTimeFactorIndex(int timeFactorIndex) {
        this.timeFactorIndex = clamp(timeFactorIndex, 0, 5);
    }

    public int getGridSize() {
        return gridRadius * 2 + 1;
    }

    public int getPhysicsGridSize() {
        return physicsGridRadius * 2 + 1;
    }

    public int getMaxy() {
        return gridHeight * chunkHeight;
    }

    /**
     * Horizontal world period in world units (worldSizeChunks * chunkSize), or 0 when the world is
     * infinite. Used as the tiling period of the terrain noise and as the half-bounds of the player.
     */
    public float getWorldSize() {
        return finiteWorld ? (float) worldSizeChunks * chunkSize : 0f;
    }

    /**
     * Wraps a world position into the canonical finite-world tile centered on the origin
     * ([-worldSize/2, +worldSize/2] in X and Z) ; Y is left untouched (the world is not vertically
     * circular). No-op when the world is infinite. Since the terrain is periodic with worldSize, the
     * wrapped position lands on identical terrain : used to bring a saved player position back near the
     * origin on load, keeping coordinates bounded across sessions with no visible jump. Mutates and
     * returns the given vector.
     */
    public Vector3f wrapToWorld(Vector3f location) {
        float w = getWorldSize();
        if (w <= 0f || location == null) {
            return location;
        }
        location.x -= w * Math.round(location.x / w);
        location.z -= w * Math.round(location.z / w);
        return location;
    }

    /**
     * Default spawn position for a world with no saved player state : centered on the origin chunk, at
     * the terrain surface plus {@link #playerStartHeight}. Used for newly created worlds (see
     * PlayerState#createPlayer and IalonConfigRepository).
     *
     * <p>The surface height is sampled at the ACTUAL spawn column (not the origin) so the player never
     * starts buried in a slope -- on steep, high-relief terrain the ground at (x, z) can be many blocks
     * above the origin's. It is also clamped to the water line so a spawn over sea starts above the
     * surface, not submerged.
     *
     * <p>On the finite world the terrain temperature is latitude-driven (hot deserts around the equator
     * Z=0, cold taiga/snow at the poles Z=±worldSize/2), so spawning on the origin column drops the
     * player in the middle of the desert. We instead spawn at Z = +worldSize/4, where the latitude
     * cosine crosses 0.5 -- the dead centre of the (upper, positive-Z) temperate band, safely clear of
     * both the hot desert band and the cold polar band even with the temperature noise wobble. On the
     * infinite world biomes are pure noise with no latitude, so the origin column is kept.
     */
    public Vector3f computeSpawnLocation() {
        float worldSize = getWorldSize();
        float x = getChunkSize() / 2f;
        float z = worldSize > 0f ? worldSize / 4f : getChunkSize() / 2f;
        float surface = Math.max(getTerrainGenerator().getHeight(new Vector3f(x, 0, z)), getWaterHeight());
        return new Vector3f(x, surface + playerStartHeight, z);
    }

    public Vec3i getGridLowerBound() {
        return new Vec3i(Integer.MIN_VALUE, 0, Integer.MIN_VALUE);
    }

    public Vec3i getGridUpperBound() {
        return new Vec3i(Integer.MAX_VALUE, gridHeight - 1, Integer.MAX_VALUE);
    }

    public ChunkManager getChunkManager() {
        if (chunkManager == null) {
            chunkManager = getDefaultChunkManager();
        }
        return chunkManager;
    }

    public ChunkRepository getChunkRepository() {
        if (chunkRepository == null) {
            chunkRepository = getDefaultChunkRepository();
        }
        return chunkRepository;
    }

    public TerrainGenerator getTerrainGenerator() {
        if (terrainGenerator == null) {
            terrainGenerator = getDefaultChunkGenerator();
        }
        return terrainGenerator;
    }

    public ChunkManager getDefaultChunkManager() {
        return ChunkManager.builder()
                .poolSize(getChunkPoolsize())
                .generator(getTerrainGenerator())
                .repository(getChunkRepository())
                .build();
    }

    public ChunkRepository getDefaultChunkRepository() {
        ZipFileRepository repository = new ZipFileRepository();
        repository.setPath(getCurrentWorldPath());
        // Finite world : key saves/edits by canonical (wrapped) coordinates so a tile is stored once
        // and edits are consistent across the seam. 0 keeps the legacy per-(x,z) storage.
        repository.setWorldSizeChunks(finiteWorld ? worldSizeChunks : 0);
        return repository;
    }

    public TerrainGenerator getDefaultChunkGenerator() {
        NoiseTerrainGenerator generator =
                new NoiseTerrainGenerator(seed, this.getWaterHeight(), this.getMaxy(), this.getWorldSize());
        generator.setReliefAmplitude(reliefAmplitude);
        generator.setReliefFrequency(reliefFrequency);
        generator.setTreeMaxProb(treeDensity);
        // forestPatchSize is the inverse of the noise frequency : scale the generator's default frequency
        // so that 1 = unchanged and larger values yield larger woods/clearings.
        generator.setForestFrequency(generator.getForestFrequency() / forestPatchSize);
        // Bind the per-world edit overlay so the far horizon honours felled trees / reshaped relief.
        generator.setWorldEditOverlay(worldEditOverlay);
        return generator;
    }

    /**
     * Filesystem directory of the currently loaded world : {@code savePath/worlds/<worldId>}. Each world
     * keeps its own chunks (chunk_*.zblock), generation parameters (world.yml) and player state
     * (player.yml) under this directory.
     */
    public Path getCurrentWorldPath() {
        return savePath.resolve(WORLDS_DIR).resolve(worldId);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(Math.min(value, max), min);
    }

    /**
     * Default size of the chunk generation/meshing thread pool, scaled to the device. Chunk
     * generation is a critical CPU path, so we use most of the available cores ; one core is left
     * for the render thread and one for physics, and the result is clamped to [2, 6] to avoid
     * oversubscription on high-core devices (which only increases contention and memory use).
     */
    private static int defaultChunkPoolSize() {
        return clamp(Runtime.getRuntime().availableProcessors() - 2, 2, 6);
    }

}
