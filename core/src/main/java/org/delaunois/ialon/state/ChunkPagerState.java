package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.Vector3f;

import org.delaunois.ialon.ChunkPager;


public class ChunkPagerState extends BaseAppState {

    private final ChunkPager chunkPager;

    public ChunkPagerState(ChunkPager chunkPager) {
        this.chunkPager = chunkPager;
    }

    public ChunkPager getChunkPager() {
        return chunkPager;
    }

    @Override
    protected void initialize(Application app) {
        chunkPager.initialize();
    }

    @Override
    protected void cleanup(Application app) {
        chunkPager.cleanup();
    }

    @Override
    protected void onEnable() {
        // Nothing to do
    }

    @Override
    protected void onDisable() {
        // Nothing to do
    }

    @Override
    public void update(float tpf) {
        chunkPager.update();
    }

    public void setLocation(Vector3f location) {
        chunkPager.setLocation(location);
    }

    public Vector3f getLocation() {
        return chunkPager.getLocation();
    }

}
