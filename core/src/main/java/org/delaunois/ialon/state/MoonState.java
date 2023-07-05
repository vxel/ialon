package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.MoonControl;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MoonState extends BaseAppState {

    private SimpleApplication app;
    private MoonControl moonControl;
    private Geometry moon;
    private final IalonConfig config;

    public MoonState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        this.app = (SimpleApplication) app;

        moon = new Geometry("moon", new Quad(15f, 15f));
        moon.setQueueBucket(RenderQueue.Bucket.Sky);
        moon.setCullHint(Spatial.CullHint.Never);
        moon.setShadowMode(RenderQueue.ShadowMode.Off);

        TextureKey tex = new TextureKey("Textures/moon.png");
        tex.setGenerateMips(false);
        Texture moonTexture = app.getAssetManager().loadTexture(tex);
        Material moonMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        moonMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        moonMat.setTexture("ColorMap", moonTexture);
        moonMat.setColor("Color", ColorRGBA.White);
        moon.setMaterial(moonMat);

        config.getTextureAtlasManager().getAtlas().applyCoords(moon, 0.1f);
        moonMat.setTexture("ColorMap", config.getTextureAtlasManager().getDiffuseMap());

        moonControl = new MoonControl(config);
        moonControl.setCam(app.getCamera());
        moon.addControl(moonControl);
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        SunState sunState = app.getStateManager().getState(SunState.class);
        if (sunState == null) {
            log.error("MoonState requires SunState");
            return;
        }
        moonControl.setSunControl(sunState.getSunControl());
        if (moon.getParent() == null) {
            this.app.getRootNode().attachChild(moon);
        }
    }

    @Override
    protected void onDisable() {
        if (moon.getParent() != null) {
            this.app.getRootNode().detachChild(moon);
        }
    }


}
