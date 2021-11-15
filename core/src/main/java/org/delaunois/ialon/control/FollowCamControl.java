package org.delaunois.ialon.control;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class FollowCamControl extends AbstractControl {

    private final Camera cam;

    @Setter
    private Vector3f translation;

    @Override
    protected void controlUpdate(float tpf) {
        if (translation != null) {
            spatial.setLocalTranslation((cam.getLocation().add(translation)));
        } else {
            spatial.setLocalTranslation((cam.getLocation()));
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {}
}
