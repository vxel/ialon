package org.delaunois.ialon;

import com.jme3.bullet.objects.PhysicsRigidBody;
import com.rvandoosselaer.blocks.PagerListener;
import com.simsilica.mathd.Vec3i;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PhysicsPagerListenerOutput implements PagerListener<PhysicsRigidBody> {

    private final Ialon app;

    public PhysicsPagerListenerOutput(Ialon app) {
        this.app = app;
    }

    @Override
    public void onPageDetached(Vec3i location, PhysicsRigidBody page) {
        log.info("Physics Chunk detached - " + location + " - " + page);
    }

    @Override
    public void onPageAttached(Vec3i location, PhysicsRigidBody page) {
        log.info("Physics Chunk attached - " + location + " - " + page);
    }

    @Override
    public void onPageUpdated(Vec3i location, PhysicsRigidBody oldPage, PhysicsRigidBody newPage) {
        log.info("Physics Chunk updated - " + location + " - " + newPage);
    }

}