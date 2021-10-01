package org.delaunois.ialon;

import com.jme3.scene.Node;
import com.rvandoosselaer.blocks.PagerListener;
import com.simsilica.mathd.Vec3i;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PagerListenerOutput implements PagerListener<Node> {

    private final Ialon app;

    public PagerListenerOutput(Ialon app) {
        this.app = app;
    }

    @Override
    public void onPageDetached(Vec3i location, Node page) {
        log.info("Chunk updated - " + location + " - " + page);
    }

    @Override
    public void onPageAttached(Vec3i location, Node page) {
        log.info("Chunk attached - " + location + " - " + page);
    }

    @Override
    public void onPageUpdated(Vec3i location, Node oldPage, Node newPage) {
        log.info("Chunk updated - " + location + " - " + newPage);
    }

}