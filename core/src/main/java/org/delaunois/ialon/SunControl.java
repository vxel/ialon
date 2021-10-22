package org.delaunois.ialon;

import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.control.AbstractControl;
import com.jme3.util.BufferUtils;

import java.nio.FloatBuffer;
import java.time.LocalTime;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.Config.AMBIANT_INTENSITY;
import static org.delaunois.ialon.Config.DAYBREAK_DURATION;
import static org.delaunois.ialon.Config.NIGHT_INTENSITY;
import static org.delaunois.ialon.Config.SUN_AMPLITUDE;
import static org.delaunois.ialon.Config.SUN_COLOR;
import static org.delaunois.ialon.Config.SUN_DAY_COLOR;
import static org.delaunois.ialon.Config.SUN_EVENING_COLOR;
import static org.delaunois.ialon.Config.SUN_HEIGHT;
import static org.delaunois.ialon.Config.SUN_INTENSITY;
import static org.delaunois.ialon.Config.TIME_FACTOR;

@Slf4j
public class SunControl extends AbstractControl {

    private static final float UPDATE_THRESHOLD = 1 / (TIME_FACTOR * 2);
    private static final ColorRGBA TRANSPARENT = new ColorRGBA(0, 0, 0, 0);

    @Getter
    @Setter
    private float time;

    @Getter
    @Setter
    private Vector3f position = new Vector3f();

    @Getter
    @Setter
    private float timeFactor = TIME_FACTOR;

    @Setter
    private DirectionalLight directionalLight;

    @Setter
    private AmbientLight ambientLight;

    @Setter
    private Camera cam;

    private final ColorRGBA sunColor = new ColorRGBA(1f, 1f, 0.4f, 1f);
    private final ColorRGBA lightColor = SUN_DAY_COLOR.clone();

    private boolean run = true;
    private long lastUpdate = System.currentTimeMillis();
    private DayPeriod dayPeriod = null;

    @Override
    protected void controlUpdate(float tpf) {
        if (!run) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastUpdate > UPDATE_THRESHOLD) {
            lastUpdate = now;
            float x = FastMath.cos(time) * 100f;
            float z = FastMath.sin(time) * 100f;
            float y = (FastMath.sin(time) * SUN_AMPLITUDE + SUN_HEIGHT) * 10f;
            position.set(x, y, z);

            if (directionalLight != null) {
                directionalLight.setDirection(position.negate());
            }

            // Y is adjusted so that there is still light when the sun is just below the horizon
            float adjustedY = y + 4f;

            if (dayPeriod == null) {
                dayPeriod = DayPeriod.DAY;
                setSunColor(sunColor, sunColor);
            }

            if (adjustedY < DAYBREAK_DURATION && adjustedY > 0) {
                if (dayPeriod == DayPeriod.DAY || dayPeriod == DayPeriod.EVENING) {
                    dayPeriod = DayPeriod.EVENING;

                    float height = adjustedY / DAYBREAK_DURATION;
                    lightColor.interpolateLocal(SUN_EVENING_COLOR, SUN_DAY_COLOR, height);
                    sunColor.interpolateLocal(ColorRGBA.Red, SUN_COLOR, height);
                    setSunColor(sunColor, sunColor);

                    directionalLight.getColor().set(lightColor.mult(height * SUN_INTENSITY));
                    ambientLight.getColor().set(lightColor.mult(Math.max(height * AMBIANT_INTENSITY, NIGHT_INTENSITY)));
                    if (log.isDebugEnabled()) {
                        log.debug("Evening. Height {}, Light color {}", height, lightColor);
                    }

                } else {
                    dayPeriod = DayPeriod.DAWN;

                    float height = adjustedY / DAYBREAK_DURATION;
                    lightColor.interpolateLocal(SUN_EVENING_COLOR, SUN_DAY_COLOR, height);
                    sunColor.interpolateLocal(ColorRGBA.Red, SUN_COLOR, height);
                    setSunColor(sunColor, sunColor);

                    directionalLight.getColor().set(lightColor.mult(height * SUN_INTENSITY));
                    ambientLight.getColor().set(lightColor.mult(Math.max(height * AMBIANT_INTENSITY, NIGHT_INTENSITY)));
                    if (log.isDebugEnabled()) {
                        log.debug("Dawn. Height {}, Light color {}", height, lightColor);
                    }
                }

            } else if (adjustedY < 0) {
                dayPeriod = DayPeriod.NIGHT;

                float height = adjustedY < -1 ? 1 : -adjustedY;
                sunColor.interpolateLocal(ColorRGBA.Red, TRANSPARENT, height);
                setSunColor(sunColor, sunColor);

                ambientLight.getColor().set(lightColor.mult(NIGHT_INTENSITY));
                directionalLight.getColor().set(lightColor.mult(NIGHT_INTENSITY));
                if (log.isDebugEnabled()) {
                    log.debug("Night. Height {}, Light color {}", height, lightColor);
                }

            } else {
                if (dayPeriod != DayPeriod.DAY) {
                    dayPeriod = DayPeriod.DAY;

                    lightColor.set(SUN_DAY_COLOR);
                    sunColor.set(SUN_COLOR);
                    setSunColor(sunColor, sunColor);
                    directionalLight.getColor().set(lightColor.mult(SUN_INTENSITY));
                    ambientLight.getColor().set(lightColor.mult(AMBIANT_INTENSITY));

                    if (log.isDebugEnabled()) {
                        log.debug("Day. Light color {}", lightColor);
                    }
                }
            }
        }

        spatial.setLocalTranslation((cam.getLocation().add(position)));
        spatial.lookAt(cam.getLocation(), Vector3f.UNIT_Y);

        time += tpf * timeFactor;
        time = time % FastMath.TWO_PI;
        if (log.isTraceEnabled()) {
            log.trace("Time is now {} ({})", getLocalTime(), FastMath.sin(time));
        }
    }

    private void setSunColor(ColorRGBA down, ColorRGBA up) {
        // Use vertex coloring to
        // - avoid needing a specific shader for the sun
        // - allow gradient when the sun is low on the horizon at almost no cost
        FloatBuffer buf = BufferUtils.createFloatBuffer(4 * 4);
        buf.put(down.r).put(down.g).put(down.b).put(down.a);
        buf.put(down.r).put(down.g).put(down.b).put(down.a);
        buf.put(up.r).put(up.g).put(up.b).put(up.a);
        buf.put(up.r).put(up.g).put(up.b).put(up.a);

        Spatial spatial = getSpatial();
        if (spatial instanceof Geometry) {
            ((Geometry) spatial).getMesh().setBuffer(VertexBuffer.Type.Color, 4, buf);
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {

    }

    public void toggleTimeRun() {
        run = !run;
    }

    public LocalTime getLocalTime() {
        return LocalTime.ofSecondOfDay((long)((time / FastMath.TWO_PI) * 86400)).plusHours(6);
    }

    private enum DayPeriod {
        DAY,
        EVENING,
        NIGHT,
        DAWN
    }
}
