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
import static org.delaunois.ialon.Config.TIME_FACTOR;

@Slf4j
public class MoonControl extends AbstractControl {

    private static final float UPDATE_THRESHOLD = 1 / (TIME_FACTOR * 2);

    @Getter
    @Setter
    private Vector3f position = new Vector3f();

    @Setter
    private SunControl sun;

    @Setter
    private Camera cam;

    private long lastUpdate = System.currentTimeMillis();

    @Override
    protected void controlUpdate(float tpf) {
        if (!sun.isRun()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastUpdate > UPDATE_THRESHOLD) {
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
