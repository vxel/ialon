package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.asset.TextureKey;
import com.jme3.input.controls.ActionListener;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.SunControl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.IalonKeyMapping.ACTION_TOGGLE_TIME_RUN;

@Slf4j
public class SunState extends BaseAppState implements ActionListener {

    private SimpleApplication app;
    private Geometry sun;

    @Getter
    private SunControl sunControl;

    private final IalonConfig config;

    public SunState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        this.app = (SimpleApplication) app;

        sun = new Geometry("Sun", new Quad(30f, 30f));
        sun.setQueueBucket(RenderQueue.Bucket.Sky);
        sun.setCullHint(Spatial.CullHint.Never);
        sun.setShadowMode(RenderQueue.ShadowMode.Off);

        TextureKey tex = new TextureKey("Textures/sun.png");
        tex.setGenerateMips(false);
        Texture sunTexture = app.getAssetManager().loadTexture(tex);
        Material sunMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        sunMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        sunMat.setTexture("ColorMap", sunTexture);
        sunMat.setColor("Color", ColorRGBA.White);
        sun.setMaterial(sunMat);

        config.getTextureAtlasManager().getAtlas().applyCoords(sun, 0.1f);
        sunMat.setTexture("ColorMap", config.getTextureAtlasManager().getDiffuseMap());

        sunControl = new SunControl(this.app.getCamera(), config);
        sun.addControl(sunControl);
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        app.getInputManager().addListener(this, ACTION_TOGGLE_TIME_RUN);

        LightingState lightingState = app.getStateManager().getState(LightingState.class);
        if (lightingState == null) {
            log.error("Sunstate requires LightingState");
            return;
        }
        sunControl.setDirectionalLight(lightingState.getDirectionalLight());
        sunControl.setAmbientLight(lightingState.getAmbientLight());
        if (sun.getParent() == null) {
            this.app.getRootNode().attachChild(sun);
        }
    }

    @Override
    protected void onDisable() {
        app.getInputManager().removeListener(this);

        if (sun.getParent() != null) {
            this.app.getRootNode().detachChild(sun);
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_TOGGLE_TIME_RUN.equals(name) && isPressed) {
            log.info("Toggle time run");
            sunControl.toggleTimeRun();
        }
    }
}
