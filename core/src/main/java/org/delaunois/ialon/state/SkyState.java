package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Cylinder;
import com.jme3.shader.VarType;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;

import org.delaunois.ialon.Ground;
import org.delaunois.ialon.Ialon;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.FollowCamControl;
import org.delaunois.ialon.control.SkyControl;

import java.nio.FloatBuffer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SkyState extends BaseAppState {

    private Ialon app;
    private SkyControl skyControl;
    private Geometry ground;
    private Geometry sky;

    private final IalonConfig config;

    public SkyState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        this.app = (Ialon) app;

        Cylinder skyCylinder = new Cylinder(2, 8, 25f, 20f, true, true);
        FloatBuffer fpb = BufferUtils.createFloatBuffer(38 * 4);
        final ColorRGBA skyColor = config.getSkyColor();
        final ColorRGBA skyHorizonColor = config.getSkyHorizonColor();
        final ColorRGBA skyZenithColor = config.getSkyZenithColor();

        fpb.put(new float[] {
                // Sides Top Vertices
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,

                // Side Bottom Vertices
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,

                // Top Cap Vertices
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,
                skyColor.r, skyColor.g, skyColor.b, skyColor.a,

                // Bottom Cap Vertices
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a,

                // Top Center Vextex
                skyZenithColor.r, skyZenithColor.g, skyZenithColor.b, skyZenithColor.a,

                // Bottom center Vertex
                skyHorizonColor.r, skyHorizonColor.g, skyHorizonColor.b, skyHorizonColor.a
        });
        skyCylinder.setBuffer(VertexBuffer.Type.Color, 4, fpb);
        sky = new Geometry("sky", skyCylinder);

        Quaternion pitch90 = new Quaternion();
        pitch90.fromAngleAxis(FastMath.HALF_PI, new Vector3f(1, 0, 0));
        sky.setLocalRotation(pitch90);

        sky.setQueueBucket(RenderQueue.Bucket.Sky);
        sky.setCullHint(Spatial.CullHint.Never);
        sky.setShadowMode(RenderQueue.ShadowMode.Off);

        Material skyMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        skyMat.setParam("VertexColor", VarType.Boolean, true );
        sky.setMaterial(skyMat);

        skyControl = new SkyControl(config);
        FollowCamControl followCamControl = new FollowCamControl(app.getCamera());
        sky.addControl(skyControl);
        sky.addControl(followCamControl);

        Ground groundPlate = new Ground(20, 20);
        ground = new Geometry("ground", groundPlate);
        ground.setQueueBucket(RenderQueue.Bucket.Sky);
        ground.setCullHint(Spatial.CullHint.Never);
        ground.setShadowMode(RenderQueue.ShadowMode.Off);

        Texture groundTexture = app.getAssetManager().loadTexture("Textures/ground.png");
        Material groundMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        groundMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        groundMat.setTexture("ColorMap", groundTexture);
        groundMat.setColor("Color", skyControl.getGroundColor());
        ground.setMaterial(groundMat);
        config.getTextureAtlasManager().getAtlas().applyCoords(ground, 0.1f);
        groundMat.setTexture("ColorMap", config.getTextureAtlasManager().getDiffuseMap());

        ground.addControl(new FollowCamControl(app.getCamera()));
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
        if (ground.getParent() == null) {
            this.app.getRootNode().attachChild(ground);
            this.app.getRootNode().attachChildAt(sky, 0);
        }
    }

    @Override
    protected void onDisable() {
        if (ground.getParent() != null) {
            this.app.getRootNode().detachChild(ground);
            this.app.getRootNode().detachChild(sky);
        }
    }


}
