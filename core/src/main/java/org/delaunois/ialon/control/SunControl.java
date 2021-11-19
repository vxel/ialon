package org.delaunois.ialon.control;

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
import static org.delaunois.ialon.Config.DAY_COLOR;
import static org.delaunois.ialon.Config.EVENING_COLOR;
import static org.delaunois.ialon.Config.NIGHT_COLOR;
import static org.delaunois.ialon.Config.SUN_AMPLITUDE;
import static org.delaunois.ialon.Config.SUN_INTENSITY;
import static org.delaunois.ialon.Config.TIME_FACTOR;

@Slf4j
public class SunControl extends AbstractControl {

    @Getter
    private float time = FastMath.HALF_PI;

    @Getter
    private final Vector3f position = new Vector3f();

    @Getter
    private float timeFactor;

    @Getter
    @Setter
    private DirectionalLight directionalLight;

    @Getter
    @Setter
    private AmbientLight ambientLight;

    @Getter
    private boolean run = true;

    @Getter
    private float sunHeight;

    private final Camera cam;
    private final ColorRGBA sunColor = new ColorRGBA(1f, 1f, 1f, 1f);
    private long lastUpdate = 0;
    private float updateThreshold = 0;

    public SunControl(Camera cam) {
        this.cam = cam;
        setTimeFactor(TIME_FACTOR);
    }

    public void setTimeFactor(float timeFactor) {
        this.timeFactor = timeFactor;
        if (timeFactor > 0) {
            updateThreshold = 1 / (timeFactor * 2);
        } else {
            updateThreshold = Float.MAX_VALUE;
        }
    }

    public void setTime(float time) {
        this.time = time;
        this.lastUpdate = 0;
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (!run) {
            return;
        }

        long now = System.currentTimeMillis();
        if (lastUpdate == 0 || now - lastUpdate > updateThreshold) {
            updateSun();
            lastUpdate = now;
        }

        spatial.setLocalTranslation((cam.getLocation().add(position)));
        spatial.lookAt(cam.getLocation(), Vector3f.UNIT_Y);

        time += tpf * timeFactor;
        time = time % FastMath.TWO_PI;
        if (log.isTraceEnabled()) {
            log.trace("Time is now {} ({})", getLocalTime(), FastMath.sin(time));
        }
    }

    private void updateSun() {
        sunHeight = FastMath.sin(time);
        float x = FastMath.cos(time) * 100f;
        float z = FastMath.sin(time) * 100f;
        float y = sunHeight * SUN_AMPLITUDE * 10f;
        position.set(x, y, z);

        if (directionalLight != null) {
            directionalLight.setDirection(position.negate());
        }

        float shift = FastMath.clamp(FastMath.pow(sunHeight * 2, 4), 0, 1);

        if (sunHeight > 0) {
            sunColor.interpolateLocal(EVENING_COLOR, DAY_COLOR, shift);
        } else {
            sunColor.interpolateLocal(EVENING_COLOR, NIGHT_COLOR, shift);
        }
        directionalLight.getColor().set(sunColor.mult(SUN_INTENSITY));
        ambientLight.getColor().set(sunColor.mult(AMBIANT_INTENSITY));

        setSunColor(sunColor, sunColor);
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

}
