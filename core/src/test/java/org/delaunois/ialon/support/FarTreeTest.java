package org.delaunois.ialon.support;

import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.TextureAtlasManager;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.state.FarTerrainState;
import org.delaunois.ialon.state.FarTreeState;

/**
 * Manual (interactive) harness to view the {@link FarTreeState} distant-tree billboards (plus the
 * {@link FarTerrainState} horizon and its forest tint) in isolation, NOT part of the automated suite.
 * Run its {@link #main(String[])} from the IDE and fly around with the standard jME fly-cam.
 *
 * <p>It feeds both states the same {@link NoiseTerrainGenerator} the game uses, so the trees scatter
 * exactly where the voxel woods would. Things to check : tree silhouettes on the far hills fading into
 * the fog, NO hard pop just beyond the inner radius, trees staying upright when looking up/down, and the
 * dark-green forest tint on the slopes beyond the billboard ring.
 */
public class FarTreeTest extends SimpleApplication {

    public static void main(String[] args) {
        FarTreeTest app = new FarTreeTest();
        app.start();
    }

    @Override
    public void simpleInitApp() {
        IalonConfig config = new IalonConfig();
        config.setTerrainGenerator(new NoiseTerrainGenerator(2, config.getWaterHeight(),
                config.getMaxy(), config.getWorldSize()));
        // The far-tree builder follows the player ; pin it to the origin for this static inspection.
        config.setPlayerLocation(new Vector3f(0f, 0f, 0f));

        // The game registers the far-tree silhouettes via IalonInitializer.setupAtlasManager ; this
        // standalone harness skips that, so pack them into the atlas here before FarTreeState resolves them.
        for (String texPath : FarTreeState.FAR_TREE_TEXTURES) {
            config.getTextureAtlasManager().addTexture(assetManager.loadTexture(texPath), TextureAtlasManager.DIFFUSE);
        }

        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-0.5f, -0.7f, -0.5f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(1.1f));
        rootNode.addLight(sun);

        AmbientLight ambient = new AmbientLight();
        ambient.setColor(ColorRGBA.White.mult(0.4f));
        rootNode.addLight(ambient);

        ColorRGBA skyColor = new ColorRGBA(0.5f, 0.65f, 0.85f, 1f);
        viewPort.setBackgroundColor(skyColor);
        config.setSkyHorizonColor(skyColor);

        stateManager.attach(new FarTerrainState(config));
        stateManager.attach(new FarTreeState(config));

        cam.setLocation(new Vector3f(0f, 180f, 700f));
        cam.lookAt(new Vector3f(0f, 40f, 0f), Vector3f.UNIT_Y);
        cam.setFrustumFar(8000f);
        flyCam.setMoveSpeed(300f);
    }
}
