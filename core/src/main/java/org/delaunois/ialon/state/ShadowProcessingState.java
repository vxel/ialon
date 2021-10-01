package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.FogFilter;
import com.jme3.shadow.DirectionalLightShadowFilter;
import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.EdgeFilteringMode;

import org.delaunois.ialon.Config;

public class ShadowProcessingState extends BaseAppState {

    private FilterPostProcessor filterPostProcessor;
    private DirectionalLightShadowRenderer dlsr;
    private FogFilter fogFilter;

    @Override
    protected void initialize(Application app) {
        filterPostProcessor = new FilterPostProcessor(app.getAssetManager());

        DirectionalLight sunLight = new DirectionalLight(new Vector3f(-0.2f, -1, -0.2f).normalizeLocal(), ColorRGBA.White.mult(Config.SUN_INTENSITY));

        fogFilter = new FogFilter();
        fogFilter.setFogColor(new ColorRGBA(0.9f, 0.9f, 0.9f, 1.0f));
        fogFilter.setFogDistance(15);
        fogFilter.setFogDensity(2.0f);
        filterPostProcessor.addFilter(fogFilter);

        DirectionalLightShadowFilter dlsf = new DirectionalLightShadowFilter(app.getAssetManager(), 4096, 1);
        dlsf.setLight(sunLight);
        dlsf.setLambda(1);
        dlsf.setEdgesThickness(4);
        dlsf.setEdgeFilteringMode(EdgeFilteringMode.Bilinear);
        filterPostProcessor.addFilter(dlsf);

        dlsr = new DirectionalLightShadowRenderer(app.getAssetManager(), 1024, 3);
        dlsr.setLight(sunLight);
    }

    @Override
    protected void cleanup(Application app) {
    }

    @Override
    protected void onEnable() {
        getApplication().getViewPort().addProcessor(dlsr);
        getApplication().getViewPort().addProcessor(filterPostProcessor);
    }

    @Override
    protected void onDisable() {
        getApplication().getViewPort().removeProcessor(dlsr);
        getApplication().getViewPort().removeProcessor(filterPostProcessor);
    }

    public FogFilter getFogFilter() {
        return fogFilter;
    }

}
