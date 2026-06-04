package org.delaunois.ialon.support;

import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.NoiseTerrainGenerator;
import org.delaunois.ialon.state.FarTerrainState;

/**
 * Manual (interactive) harness to view the {@link FarTerrainState} far horizon in isolation,
 * NOT part of the automated suite. Run its {@link #main(String[])} from the IDE and fly around
 * with the standard jME fly-cam (WASD + mouse, QZ for up/down).
 *
 * <p>It feeds the far terrain the same {@link NoiseTerrainGenerator} the game uses, so the relief
 * shown here is exactly what the voxel world would produce.
 */
public class FarTerrainTest extends SimpleApplication {

    public static void main(String[] args) {
        FarTerrainTest app = new FarTerrainTest();
        app.start();
    }

    @Override
    public void simpleInitApp() {
        IalonConfig config = new IalonConfig();
        config.setTerrainGenerator(new NoiseTerrainGenerator(2, config.getWaterHeight()));

        // Sun + ambient so the Lighting material reveals the relief through the terrain normals.
        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-0.5f, -0.7f, -0.5f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(1.1f));
        rootNode.addLight(sun);

        AmbientLight ambient = new AmbientLight();
        ambient.setColor(ColorRGBA.White.mult(0.4f));
        rootNode.addLight(ambient);

        // Sky-ish background to gauge the horizon. The fog now lives in the FarTerrain material, so
        // it fades only the terrain toward the horizon colour — the background/sky stays untouched.
        ColorRGBA skyColor = new ColorRGBA(0.5f, 0.65f, 0.85f, 1f);
        viewPort.setBackgroundColor(skyColor);
        // The far terrain fogs toward the horizon colour : align it with this harness background.
        config.setSkyHorizonColor(skyColor);

        stateManager.attach(new FarTerrainState(config));

        // High vantage point looking toward the horizon, fast fly-cam to roam the island.
        cam.setLocation(new Vector3f(0f, 180f, 700f));
        cam.lookAt(new Vector3f(0f, 40f, 0f), Vector3f.UNIT_Y);
        cam.setFrustumFar(8000f);
        flyCam.setMoveSpeed(300f);
    }
}
