package org.delaunois.ialon.control;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Makes the camera follow the translation of a spatial, with an optional local translation.
 */
@Slf4j
public class CamFollowSpatialControl extends AbstractControl {

    private final Camera camera;
    private final Vector3f tmp = new Vector3f();

    @Setter
    private Vector3f localTranslation;

    public CamFollowSpatialControl(Camera camera) {
        this.camera = camera;
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (localTranslation != null) {
            tmp.set(spatial.getWorldTranslation()).addLocal(localTranslation);
            camera.setLocation(tmp);
        } else {
            camera.setLocation(spatial.getWorldTranslation());
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

}
