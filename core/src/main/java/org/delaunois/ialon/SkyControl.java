package org.delaunois.ialon;

import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.control.AbstractControl;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.Config.SKY_DAY_COLOR;
import static org.delaunois.ialon.Config.SKY_EVENING_COLOR;
import static org.delaunois.ialon.Config.SKY_NIGHT_COLOR;
import static org.delaunois.ialon.Config.TIME_FACTOR;

@Slf4j
public class SkyControl extends AbstractControl {

    private static final float UPDATE_THRESHOLD = 1 / (TIME_FACTOR * 2);

    @Setter
    private Camera cam;

    @Setter
    private SunControl sun;

    private long lastUpdate = System.currentTimeMillis();

    private final ColorRGBA color = new ColorRGBA();

    @Override
    protected void controlUpdate(float tpf) {
        spatial.setLocalTranslation((cam.getLocation()));

        long now = System.currentTimeMillis();
        if (now - lastUpdate > UPDATE_THRESHOLD) {
            lastUpdate = now;

            float sunHeight = sun.getSunHeight();
            float shift = FastMath.clamp(FastMath.pow(sunHeight * 2, 4), 0, 1);

            //log.info("time:{} sunHeight:{}, shift:{}", sun.getLocalTime(), sunHeight, shift);

            if (sunHeight > 0) {
                color.interpolateLocal(SKY_EVENING_COLOR, SKY_DAY_COLOR, shift);
            } else {
                color.interpolateLocal(SKY_EVENING_COLOR, SKY_NIGHT_COLOR, shift);
            }
            ((Geometry) spatial).getMaterial().setColor("Color", color);
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {}
}
