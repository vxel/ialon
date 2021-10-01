package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.TechniqueDef;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;

import org.delaunois.ialon.Config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LightingState extends BaseAppState {

    private final Vector3f directionalLightDir = new Vector3f(-0.2f, -1, -0.2f).normalizeLocal();

    private Node node;

    private final ColorRGBA ambientLightColor = ColorRGBA.White.mult(Config.AMBIANT_INTENSITY);

    private final ColorRGBA directionalLightColor = ColorRGBA.White.mult(Config.SUN_INTENSITY);

    @Getter
    private final AmbientLight ambientLight = new AmbientLight(ambientLightColor);

    @Getter
    private final DirectionalLight directionalLight = new DirectionalLight(directionalLightDir, directionalLightColor);

    @Override
    protected void initialize(Application app) {
        app.getRenderManager().setPreferredLightMode(TechniqueDef.LightMode.SinglePass);
        app.getRenderManager().setSinglePassLightBatchSize(2);
    }

    @Override
    protected void cleanup(Application app) {
    }

    @Override
    protected void onEnable() {
        if (node == null) {
            node = ((SimpleApplication) getApplication()).getRootNode();
        }

        attachLights();
    }

    @Override
    protected void onDisable() {
        detachLights();
    }

    @Override
    public void update(float tpf) {
    }

    private void attachLights() {
        node.addLight(ambientLight);
        node.addLight(directionalLight);
    }

    private void detachLights() {
        node.removeLight(ambientLight);
        node.removeLight(directionalLight);
    }

}
