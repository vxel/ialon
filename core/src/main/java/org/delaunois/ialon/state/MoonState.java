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
import org.delaunois.ialon.control.MoonControl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MoonState extends BaseAppState {

    private Ialon app;

    @Getter
    private MoonControl moonControl;

    private Geometry moon;

    @Override
    protected void initialize(Application app) {
        this.app = (Ialon) app;

        moon = new Geometry("moon", new Quad(15f, 15f));
        moon.setQueueBucket(RenderQueue.Bucket.Sky);
        moon.setCullHint(Spatial.CullHint.Never);
        moon.setShadowMode(RenderQueue.ShadowMode.Off);

        Texture moonTexture = app.getAssetManager().loadTexture("Textures/moon.png");
        Material moonMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        moonMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        moonMat.setTexture("ColorMap", moonTexture);
        moonMat.setColor("Color", ColorRGBA.White);
        moon.setMaterial(moonMat);

        this.app.getAtlasManager().getAtlas().applyCoords(moon, 0.1f);
        moonMat.setTexture("ColorMap", this.app.getAtlasManager().getDiffuseMap());

        moonControl = new MoonControl();
        moonControl.setCam(app.getCamera());
        moon.addControl(moonControl);
    }

    @Override
    protected void cleanup(Application app) {
    }

    @Override
    protected void onEnable() {
        SunState sunState = app.getStateManager().getState(SunState.class);
        if (sunState == null) {
            log.error("MoonState requires SunState");
            return;
        }
        moonControl.setSun(sunState.getSunControl());
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
