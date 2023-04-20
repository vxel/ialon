package org.delaunois.ialon;

import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.Chunk;
import com.simsilica.mathd.Vec3i;

public class EmptyGenerator implements TerrainGenerator {

    @Override
    public Chunk generate(Vec3i location) {
        return Chunk.createAt(location);
    }

    public float getWaterHeight() {
        throw new UnsupportedOperationException("Water is not supported by " + this.getClass().getSimpleName());
    }

    public void setWaterHeight(float waterHeight) {
        throw new UnsupportedOperationException("Water is not supported by " + this.getClass().getSimpleName());
    }

    public float getHeight(Vector3f blockLocation) {
        return 0;
    }

}