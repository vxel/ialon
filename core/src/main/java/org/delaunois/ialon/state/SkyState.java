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

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.SkyControl;
import org.delaunois.ialon.control.SpatialFollowCamControl;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SkyState extends BaseAppState {

    private SimpleApplication app;
    @lombok.Getter
    private SkyControl skyControl;
    private Geometry sky;

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
        Cylinder skyCylinder = new Cylinder(2, 8, 25f, 20f, true, true);
        return new Geometry("sky", skyCylinder);
    }

}
