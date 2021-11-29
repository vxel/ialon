package org.delaunois.ialon.fastnoise;

public class LayerMask {

    private NoiseLayer noiseLayer;
    private NoiseLayer withLayer;

    public LayerMask(NoiseLayer noiseLayer, NoiseLayer withLayer) {
        this.noiseLayer = noiseLayer;
        this.withLayer = withLayer;
    }

    public NoiseLayer getNoiseLayer() {
        return noiseLayer;
    }

    public NoiseLayer getWithLayer() {
        return withLayer;
    }

    @Override
    public String toString() {
        return noiseLayer.getName() + " -> " + withLayer.getName();
    }

}
