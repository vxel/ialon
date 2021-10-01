package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.ColorRGBA;
import com.jme3.post.filters.FogFilter;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Slider;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.component.TbtQuadBackgroundComponent;
import com.simsilica.lemur.core.VersionedReference;
import com.simsilica.lemur.event.CursorEventControl;
import com.simsilica.lemur.event.DragHandler;
import com.simsilica.lemur.style.ElementId;

public class CameraDebugState extends BaseAppState {

    private Node node;
    private Container container;

    private VersionedReference<Double> frustrumNearReference;
    private Label frustrumNearValue;

    private VersionedReference<Double> fovReference;
    private Label fovValue;

    private VersionedReference<Double> fogDistanceReference;
    private Label fogDistanceValue;

    private VersionedReference<Double> fogDensityReference;
    private Label fogDensityValue;

    private FogFilter fogFilter;
    private Camera cam;

    @Override
    protected void initialize(Application app) {
        ShadowProcessingState shadowProcessingState = getStateManager().getState(ShadowProcessingState.class);
        if (shadowProcessingState != null) {
            fogFilter = shadowProcessingState.getFogFilter();
        }

        cam = app.getCamera();

        container = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        ColorRGBA colorRGBA = ((TbtQuadBackgroundComponent) container.getBackground()).getColor();
        colorRGBA.set(colorRGBA.r, colorRGBA.g, colorRGBA.b, 0.9f);
        Label title = container.addChild(new Label("Camera", new ElementId("title")));
        DragHandler dragHandler = new DragHandler(input -> container);
        CursorEventControl.addListenersToSpatial(title, dragHandler);

        container.addChild(new Label("FrustrumNear", new ElementId("title")));
        Container shorelineRow = container.addChild(createRow());
        shorelineRow.addChild(new Label("FrustrumNear value: "));
        frustrumNearValue = shorelineRow.addChild(new Label(Float.toString(cam.getFrustumNear())));
        Slider shoreline = shorelineRow.addChild(createSlider(0.1f, 0.1f, 20, cam.getFrustumNear()));
        frustrumNearReference = shoreline.getModel().createReference();

        container.addChild(new Label("FOV", new ElementId("title")));
        Container fovRow = container.addChild(createRow());
        fovRow.addChild(new Label("FOV value: "));
        fovValue = fovRow.addChild(new Label(Float.toString(cam.getFov())));
        Slider fov = fovRow.addChild(createSlider(0.1f, 0.1f, 500, cam.getFov()));
        fovReference = fov.getModel().createReference();

        if (fogFilter != null) {
            container.addChild(new Label("Fog Distance", new ElementId("title")));
            Container fogDistanceRow = container.addChild(createRow());
            fogDistanceRow.addChild(new Label("Fog Distance: "));
            fogDistanceValue = fogDistanceRow.addChild(new Label(Float.toString(fogFilter.getFogDistance())));
            Slider fogDistanceSlider = fogDistanceRow.addChild(createSlider(10f, 1f, 100000, fogFilter.getFogDistance()));
            fogDistanceReference = fogDistanceSlider.getModel().createReference();

            container.addChild(new Label("Fog Density", new ElementId("title")));
            Container fogDensityRow = container.addChild(createRow());
            fogDensityRow.addChild(new Label("Fog Density: "));
            fogDensityValue = fogDensityRow.addChild(new Label(Float.toString(fogFilter.getFogDensity())));
            Slider fogDensitySlider = fogDensityRow.addChild(createSlider(0.1f, 0.1f, 100, fogFilter.getFogDensity()));
            fogDensityReference = fogDensitySlider.getModel().createReference();

        }
        container.setLocalTranslation(0, cam.getHeight(), 99);
    }

    @Override
    protected void cleanup(Application app) {
    }

    @Override
    protected void onEnable() {
        if (node == null) {
            node = ((SimpleApplication) getApplication()).getGuiNode();
        }

        node.attachChild(container);
    }

    @Override
    protected void onDisable() {
        container.removeFromParent();
    }

    @Override
    public void update(float tpf) {
        if (frustrumNearReference.update()) {
            cam.setFrustumNear(frustrumNearReference.get().floatValue());
            cam.update();
            frustrumNearValue.setText(String.format("%.1f", frustrumNearReference.get()));
        }

        if (fovReference.update()) {
            cam.setFov(fovReference.get().floatValue());
            cam.update();
            fovValue.setText(String.format("%.1f", fovReference.get()));
        }

        if (fogFilter != null) {
            if (fogDensityReference.update()) {
                fogFilter.setFogDensity(fogDensityReference.get().floatValue());
                fogDensityValue.setText(String.format("%.1f", fogDensityReference.get()));
            }

            if (fogDistanceReference.update()) {
                fogFilter.setFogDistance(fogDistanceReference.get().floatValue());
                fogDistanceValue.setText(String.format("%.1f", fogDistanceReference.get()));
            }
        }
    }

    private Container createRow() {
        return new Container(new SpringGridLayout(Axis.X, Axis.Y, FillMode.ForcedEven, FillMode.Even));
    }

    private Slider createSlider(float delta, float min, float max, float value) {
        Slider slider = new Slider(Axis.X);
        slider.setDelta(delta);
        slider.getModel().setMinimum(min);
        slider.getModel().setMaximum(max);
        slider.getModel().setValue(value);

        return slider;
    }

}
