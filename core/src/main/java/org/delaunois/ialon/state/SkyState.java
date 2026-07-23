package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Cylinder;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.control.SkyControl;
import org.delaunois.ialon.control.SpatialFollowCamControl;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SkyState extends BaseAppState {

    // Sky cylinder dimensions. The rim elevation derived from them (see CAP_ELEVATION) is what splits the
    // top cap from the side walls in Sky.frag, so the two must stay in step -- hence the named constants.
    private static final float SKY_RADIUS = 25f;
    private static final float SKY_HEIGHT = 20f;
    // dir.y of the cylinder's rim, as seen from its centre (where the camera sits) : a view direction above
    // this lands on the top cap, below it on a side wall or the bottom cap.
    private static final float CAP_ELEVATION =
            (SKY_HEIGHT / 2f) / (float) Math.sqrt(SKY_RADIUS * SKY_RADIUS + (SKY_HEIGHT / 2f) * (SKY_HEIGHT / 2f));

    // The under-surface test walks a whole voxel column (WorldManager.groundHeight), so it is cached and
    // only redone when the player moves to another block column -- plus this slow refresh, which is what
    // picks up the player digging the ceiling of the column they are standing in.
    private static final long SURFACE_REFRESH_MS = 1000;

    private SimpleApplication app;
    @lombok.Getter
    private SkyControl skyControl;
    private Geometry sky;

    // Cached column surface : the world Y of the topmost terrain block in the player's current column, or
    // NaN when that column is not loaded (treated as "not under the surface", i.e. sky as usual).
    private int surfaceCellX = Integer.MIN_VALUE;
    private int surfaceCellZ = Integer.MIN_VALUE;
    private float columnSurfaceY = Float.NaN;
    private long lastSurfaceScanMs = 0;
    // Last value pushed to the shader, so the uniform is only written when it actually flips.
    private float lastUnderSurface = Float.NaN;
    /**
     * Whether the eye is in the depths : below its column's terrain surface (see {@link #isUnderSurface})
     * <b>and</b> below the world water level. Both clauses matter — being under the surface alone is not
     * enough, because a gallery opening on a mountainside is under its column's surface yet has a genuine
     * view of the landscape, and blacking that out would blank a legitimate vista. Below the water line
     * there is no such view to protect.
     *
     * <p>Computed here because this state is always attached, while {@code FarTerrainState} can be detached
     * entirely by the far-terrain setting — so the dependency has to run this way round. It drives the sky's
     * own blackout and is read by {@code FarTerrainState} / {@code FarTreeState} to hide the distant
     * horizon, so all three flip on exactly the same condition.
     */
    @lombok.Getter
    private boolean deepUnderground;

    private final IalonConfig config;

    public SkyState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        this.app = (SimpleApplication) app;

        sky = createSkyGeometry();
        Quaternion pitch90 = new Quaternion();
        pitch90.fromAngleAxis(FastMath.HALF_PI, new Vector3f(1, 0, 0));
        sky.setLocalRotation(pitch90);

        sky.setQueueBucket(RenderQueue.Bucket.Sky);
        sky.setCullHint(Spatial.CullHint.Never);
        sky.setShadowMode(RenderQueue.ShadowMode.Off);

        Material skyMat = new Material(app.getAssetManager(), "Shaders/Sky.j3md");
        // Static gradient stops + glow colour : authored linear (IalonConfig setAsSrgb), encoded
        // in-shader where the hardware sRGB framebuffer is missing (Android GLES), like the voxels.
        skyMat.setColor("HorizonColor", config.getSkyHorizonColor());
        skyMat.setColor("SkyColor", config.getSkyColor());
        skyMat.setColor("ZenithColor", config.getSkyZenithColor());
        skyMat.setColor("GlowColor", config.getSkyEveningColor());
        skyMat.setFloat("ZenithExponent", 0.5f);
        skyMat.setFloat("GlowSharpness", 8f);
        // Under-surface blackout : all cylinder walls black except the top cap. Driven from update().
        skyMat.setFloat("CapElevation", CAP_ELEVATION);
        skyMat.setFloat("UnderSurface", 0f);
        // Dynamic uniforms (Color, SunDirection, GlowStrength) are driven by SkyControl.
        skyMat.setBoolean("ManualSrgb", config.isManualGammaEncode());
        sky.setMaterial(skyMat);

        skyControl = new SkyControl(config);
        SpatialFollowCamControl followCamControl = new SpatialFollowCamControl(app.getCamera());
        sky.addControl(skyControl);
        sky.addControl(followCamControl);
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    public void update(float tpf) {
        Vector3f eye = app.getCamera().getLocation();
        deepUnderground = eye.y < config.getWaterHeight() && isUnderSurface(eye);
        float under = deepUnderground ? 1f : 0f;
        if (under != lastUnderSurface) {
            lastUnderSurface = under;
            sky.getMaterial().setFloat("UnderSurface", under);
        }
    }

    /**
     * True when the eye is below the terrain surface of its own column, i.e. inside the ground : in a cave,
     * a tunnel, or plain solid rock.
     *
     * <p>The surface is the topmost {@link org.delaunois.ialon.blocks.Block#isTerrain()} cell
     * ({@link WorldManager#groundHeight}), which is what makes this the right test rather than a height
     * field: it comes from the real voxel data (so it follows the player's own digging) and it ignores
     * trees, water and built structures, so standing under a canopy or inside a house is NOT "under the
     * surface". An unloaded column yields NaN and is treated as not under, so the sky stays normal.
     *
     * <p>That scan walks a column, so its result is cached per block column and refreshed only when the
     * player changes column or {@link #SURFACE_REFRESH_MS} elapses — never per frame.
     */
    private boolean isUnderSurface(Vector3f eye) {
        ChunkManager chunkManager = config.getChunkManager();
        if (chunkManager == null) {
            return false;
        }
        Vec3i cell = ChunkManager.getBlockLocation(eye);
        long now = System.currentTimeMillis();
        if (cell.x != surfaceCellX || cell.z != surfaceCellZ || now - lastSurfaceScanMs > SURFACE_REFRESH_MS) {
            surfaceCellX = cell.x;
            surfaceCellZ = cell.z;
            lastSurfaceScanMs = now;
            columnSurfaceY = WorldManager.groundHeight(chunkManager, cell.x, cell.z, config.getMaxy());
        }
        return !Float.isNaN(columnSurfaceY) && eye.y < columnSurfaceY;
    }

    @Override
    protected void onEnable() {
        SunState sunState = app.getStateManager().getState(SunState.class);
        if (sunState == null) {
            log.error("SkyState requires SunState");
            return;
        }
        skyControl.setSunControl(sunState.getSunControl());
        if (sky.getParent() == null) {
            this.app.getRootNode().attachChildAt(sky, 0);
        }
    }

    @Override
    protected void onDisable() {
        if (sky.getParent() != null) {
            this.app.getRootNode().detachChild(sky);
        }
    }

    private Geometry createSkyGeometry() {
        // The cylinder only has to surround the camera : the gradient is computed per fragment from the
        // view-direction elevation (Shaders/Sky.frag), so the coarse tessellation no longer matters.
        Cylinder skyCylinder = new Cylinder(2, 8, SKY_RADIUS, SKY_HEIGHT, true, true);
        return new Geometry("sky", skyCylinder);
    }

}
