package org.delaunois.ialon.fastnoise;

import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;

import java.util.ArrayList;
import java.util.List;

public class LayeredNoise {

    private final List<NoiseLayer> layers = new ArrayList<>();
    private final List<LayerMask> layerMasks = new ArrayList<>();

    private boolean hardFloor;
    private float hardFloorHeight = 10;
    private float hardFloorStrength = 0.5f;

    public LayeredNoise() {
    }

    public void addLayer(NoiseLayer noiseLayer) {
        layers.add(noiseLayer);
    }

    public void removeLayer(NoiseLayer noiseLayer) {
        layers.remove(noiseLayer);
    }

    public List<NoiseLayer> getLayers() {
        return layers;
    }

    public List<LayerMask> getLayerMasks() {
        return this.layerMasks;
    }

    public boolean addLayerMask(LayerMask layerMask) {
        return this.layerMasks.add(layerMask);
    }

    public boolean removeLayerMask(LayerMask layerMask) {
        return this.layerMasks.remove(layerMask);
    }

    public boolean removeLayerMaskFrom(NoiseLayer noiseLayer) {
        return this.layerMasks.removeIf(mask -> mask.getNoiseLayer().equals(noiseLayer));
    }

    public boolean isHardFloor() {
        return hardFloor;
    }

    public void setHardFloor(boolean hardFloor) {
        this.hardFloor = hardFloor;
    }

    public float getHardFloorHeight() {
        return hardFloorHeight;
    }

    public void setHardFloorHeight(float hardFloorHeight) {
        this.hardFloorHeight = hardFloorHeight;
    }

    public float getHardFloorStrength() {
        return hardFloorStrength;
    }

    public void setHardFloorStrength(float hardFloorStrength) {
        this.hardFloorStrength = hardFloorStrength;
    }

    public float evaluate(Vector2f v) {

        float result = 0;

        for (NoiseLayer layer : layers) {

            LayerMask mask = null;
            if (layerMasks.size() > 0) {
                mask = layerMasks.stream()
                        .filter(m -> m.getNoiseLayer().getName().equals(layer.getName()))
                        .findFirst()
                        .orElse(null);
            }

            float layerNoise = layer.evaluate(v);

            if (mask != null) {
                layerNoise *= mask.getWithLayer().evaluate(v);
            }

            layerNoise *= layer.getStrength();
            result += layerNoise;
        }

        if (hardFloor) {

            // density += saturate((hard_floor_y - ws_orig.y)*3)*40;

            result += FastMath.saturate((hardFloorHeight - result) * 3.0f)
                    * ((hardFloorHeight - result) * hardFloorStrength);

        }

        return result;
    }

    private float smoothstep(final float a, final float b, final float x) {
        if (x < a) {
            return 0;
        } else if (x > b) {
            return 1;
        }
        float xx = (x - a) / (b - a);
        return xx * xx * (3 - 2 * xx);
    }

}
