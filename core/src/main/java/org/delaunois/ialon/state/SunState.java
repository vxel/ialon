package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture;

import org.delaunois.ialon.Ialon;
import org.delaunois.ialon.control.SunControl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SunState extends BaseAppState {

    private Ialon app;

    @Getter
    private SunControl sunControl;

    private Geometry sun;

    private float time;

    @Override
    protected void initialize(Application app) {
        this.app = (Ialon) app;

        sun = new Geometry("Sun", new Quad(30f, 30f));
        sun.setQueueBucket(RenderQueue.Bucket.Sky);
        sun.setCullHint(Spatial.CullHint.Never);
        sun.setShadowMode(RenderQueue.ShadowMode.Off);

        Texture sunTexture = app.getAssetManager().loadTexture("Textures/sun.png");
        Material sunMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        sunMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        sunMat.setTexture("ColorMap", sunTexture);
        sunMat.setColor("Color", ColorRGBA.White);
        sun.setMaterial(sunMat);

        this.app.getAtlasManager().getAtlas().applyCoords(sun, 0.1f);
        sunMat.setTexture("ColorMap", this.app.getAtlasManager().getDiffuseMap());

        sunControl = new SunControl(this.app.getCamera());
        sun.addControl(sunControl);
    }

    public void setTime(float time) {
        this.time = time;
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        LightingState lightingState = app.getStateManager().getState(LightingState.class);
        if (lightingState == null) {
            log.error("Sunstate requires LightingState");
            return;
        }
        sunControl.setTime(time);
        sunControl.setDirectionalLight(lightingState.getDirectionalLight());
        sunControl.setAmbientLight(lightingState.getAmbientLight());
        if (sun.getParent() == null) {
            this.app.getRootNode().attachChild(sun);
        }
    }

    @Override
    protected void onDisable() {
        if (sun.getParent() != null) {
            this.app.getRootNode().detachChild(sun);
        }
    }


}
