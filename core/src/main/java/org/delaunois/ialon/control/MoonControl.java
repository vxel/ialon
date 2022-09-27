package org.delaunois.ialon.control;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.Config.SUN_AMPLITUDE;

@Slf4j
public class MoonControl extends AbstractControl {

    @Getter
    @Setter
    private Vector3f position = new Vector3f();

    @Setter
    private SunControl sun;

    @Setter
    private Camera cam;

    private long lastUpdate = 0;

    @Override
    protected void controlUpdate(float tpf) {
        if (!sun.isRun()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (lastUpdate == 0 || now - lastUpdate > sun.getUpdateThreshold()) {
            lastUpdate = now;
            float time = sun.getTime() + FastMath.PI;
            float height = FastMath.sin(time);
            float x = FastMath.cos(time) * 100f;
            float z = FastMath.sin(time) * 100f;
            float y = height * SUN_AMPLITUDE * 10f;
            position.set(x, y, z);
        }

        spatial.setLocalTranslation((cam.getLocation().add(position)));
        spatial.lookAt(cam.getLocation(), Vector3f.UNIT_Y);
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {

    }

}
