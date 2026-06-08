package org.delaunois.ialon;

import com.jme3.math.Vector3f;
import org.delaunois.ialon.blocks.ChunkGenerator;

public interface TerrainGenerator extends ChunkGenerator {

    float getWaterHeight();

    void setWaterHeight(float waterHeight);

    float getHeight(Vector3f blockLocation);
}
