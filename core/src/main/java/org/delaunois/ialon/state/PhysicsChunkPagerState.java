package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.math.Vector3f;

import org.delaunois.ialon.ChunkManager;
import org.delaunois.ialon.PhysicsChunkPager;

import lombok.Getter;

/**
 * An AppState implementation that manages the lifecycle of a {@link PhysicsChunkPager}.
 *
 * @author rvandoosselaer
 */
public class PhysicsChunkPagerState extends BaseAppState {

    @Getter
    private final PhysicsChunkPager physicsChunkPager;

    public PhysicsChunkPagerState(PhysicsChunkPager physicsChunkPager) {
        this.physicsChunkPager = physicsChunkPager;
    }

    public PhysicsChunkPagerState(PhysicsSpace physicsSpace, ChunkManager chunkManager) {
        this(new PhysicsChunkPager(physicsSpace, chunkManager));
    }

    @Override
    protected void initialize(Application app) {
        physicsChunkPager.initialize();
    }

    @Override
    protected void cleanup(Application app) {
        physicsChunkPager.cleanup();
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
        physicsChunkPager.update();
    }

    public void setPhysicsSpace(PhysicsSpace physicsSpace) {
        physicsChunkPager.setPhysicsSpace(physicsSpace);
    }

    public PhysicsSpace getPhysicsSpace() {
        return physicsChunkPager.getPhysicsSpace();
    }

    public void setLocation(Vector3f location) {
        physicsChunkPager.setLocation(location);
    }

    public Vector3f getLocation() {
        return physicsChunkPager.getLocation();
    }
}
