package org.delaunois.ialon;

import com.jme3.font.BitmapFont;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.CameraNode;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.ChunkRepository;
import com.simsilica.mathd.Vec3i;

import java.nio.file.FileSystems;
import java.nio.file.Path;

import lombok.Getter;
import lombok.Setter;

/**
 * Global configuration options for Ialon
 */
@Getter
@Setter
public class IalonConfig {

    public static final String SAVEDIR = "./save";
    public static final String CHUNK_NODE_NAME = "chunk-node";
    public static final String FONT_PATH = "Textures/font-apex.fnt";
    public static final int FPS_LIMIT_MOBILE = 100;
    public static final int FPS_LIMIT_DESKTOP = 120;

    // Screen - Rendering
    private float gammaCorrection = -1f; // Disabled. Used only for Android.
    private int screenWidth = 1520;
    private int screenHeight = 720;
    private int maxUpdatePerFrame = 8;
    private int chunkPoolsize = 2;

    // Grid
    private int gridRadius = 4;
    private int gridRadiusMin = 0;
    private int gridRadiusMax = 15;
    private int physicsGridRadius = 1;
    private int chunkSize = 16;
    private int chunkHeight = 16;
    private int gridHeight = 7;

    // World

    // Finite world : when true the world is a torus -- the terrain is seamless and periodic in X and
    // Z with period worldSize (= worldSizeChunks * chunkSize world units), so the -x/+x and -z/+z
    // edges join perfectly. The player roams freely (no runtime bound) but its saved position is wrapped
    // back into the origin-centered tile on load (see wrapToWorld), and saves/edits are keyed modulo
    // worldSizeChunks so content is finite and consistent across the seam. When false the world is the
    // legacy infinite, non-tiling terrain.
    private boolean finiteWorld = true;
    private int worldSizeChunks = 256; // square world side, in chunks (256 * 16 = 4096 world units)

    // Far terrain : a distant low-detail heightmap horizon rendered beyond the loaded chunks.
    private boolean farTerrain = true;
    private float farTerrainFogDistance = 2500f;
    private float farTerrainFogDensity = 5f;
    private ColorRGBA farTerrainBaseColor = new ColorRGBA(0.42f, 0.667f, 0.221f, 1f); // grass / land : mean albedo of the voxel grass texture (ialon-theme/grass.png) so near & far land read alike
    private ColorRGBA farTerrainWaterColor = new ColorRGBA(0.15f, 0.59f, 0.78f, 1f); // teal-blue, matched to the voxel water texture (ialon-theme/water_calm.png) so near & far seas read as one body
    private ColorRGBA farTerrainSandColor = new ColorRGBA(1.0f, 0.966f, 0.725f, 1f); // mean albedo of the voxel sand texture (ialon-theme/sand.png)
    private ColorRGBA farTerrainRockColor = new ColorRGBA(0.48f, 0.47f, 0.46f, 1f); // bare rock (high mountains)
    private ColorRGBA farTerrainSnowColor = new ColorRGBA(0.92f, 0.94f, 0.97f, 1f); // snow caps (highest peaks)
    private float farTerrainExtent = 4096f; // world span covered by the far terrain, centered on origin
    private float farTerrainVerticalOffset = 1f; // fine vertical nudge of the far terrain to best line up with the voxel surface at the seam (poke-through/z-fighting are handled by farTerrainDepthBias)
    private float farTerrainDepthBias = 0.1f; // clip-space depth bias : voxels win the depth test over the far terrain (prevents poke-through)

    private float waterHeight = 30;
    // Calm water rendering : the flat surface of still (source) water is rendered as merged, flat-coloured
    // quads (greedy meshing) instead of one textured, scrolling quad per block. Huge triangle savings on
    // large seas/lakes, at the cost of dropping the surface texture/animation (calmWaterColor is used).
    private boolean greedyCalmWater = true;
    private ColorRGBA calmWaterColor = new ColorRGBA(0.15f, 0.41f, 0.55f, 0.92f); // mean albedo+alpha of ialon-theme/water_calm.png
    private boolean simulateLiquidFlow = true;
    private int simulateLiquidFlowModel = 2;
    private float waterSimulationSpeed = 4f;
    private float ambiantIntensity = 0.55f;
    private float sunIntensity = 1.0f;
    private float sunAmplitude = 10f;

    private ColorRGBA skyColor = ColorRGBA.fromRGBA255(100, 172, 255, 255);
    private ColorRGBA skyZenithColor = ColorRGBA.fromRGBA255(65, 142, 255, 255);
    private ColorRGBA skyHorizonColor = ColorRGBA.White;
    // Background well below the horizon (nadir) : the sky dome floor + ground plate fade to this,
    // so looking down past the terrain shows a dark void instead of the light-blue sky ground.
    private ColorRGBA skyFloorColor = new ColorRGBA(0.15f, 0.59f, 0.78f, 1f);

    private ColorRGBA skyDayColor = ColorRGBA.White;
    private ColorRGBA skyEveningColor = new ColorRGBA(1f, 0.7f, 0.5f, 1);
    private ColorRGBA skyNightColor = ColorRGBA.fromRGBA255(9, 12, 19, 255);
    private ColorRGBA groundDayColor = ColorRGBA.fromRGBA255(141, 199, 255, 255);
    private ColorRGBA groundEveningColor = ColorRGBA.fromRGBA255(150, 136, 126, 255);
    private ColorRGBA groundNightColor = ColorRGBA.fromRGBA255(6, 6, 12, 255);
    private ColorRGBA dayColor = ColorRGBA.White;
    private ColorRGBA eveningColor = ColorRGBA.fromRGBA255(255, 173, 66, 255);
    private ColorRGBA nightColor = new ColorRGBA(0.2f, 0.2f, 0.2f, 1);

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
    private final TextureAtlasManager textureAtlasManager = new TextureAtlasManager();
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
        repository.setPath(getSavePath());
        // Finite world : key saves/edits by canonical (wrapped) coordinates so a tile is stored once
        // and edits are consistent across the seam. 0 keeps the legacy per-(x,z) storage.
        repository.setWorldSizeChunks(finiteWorld ? worldSizeChunks : 0);
        return repository;
    }

    public TerrainGenerator getDefaultChunkGenerator() {
        return new NoiseTerrainGenerator(2, this.getWaterHeight(), this.getMaxy(), this.getWorldSize());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(Math.min(value, max), min);
    }

}
