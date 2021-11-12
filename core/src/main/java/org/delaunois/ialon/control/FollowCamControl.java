package org.delaunois.ialon.control;

import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class FollowCamControl extends AbstractControl {

    private final Camera cam;

    @Override
    protected void controlUpdate(float tpf) {
        spatial.setLocalTranslation((cam.getLocation()));
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {}
}
