package org.delaunois.ialon.support;

import com.jme3.app.FlyCamAppState;
import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;
import com.jme3.scene.shape.Sphere;
import com.jme3.terrain.geomipmap.TerrainQuad;
import com.jme3.terrain.heightmap.ImageBasedHeightMap;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.FrameBuffer.FrameBufferTarget;
import com.jme3.texture.Image;
import com.jme3.texture.Image.Format;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.simsilica.lemur.GuiGlobals;

public class MapTest extends SimpleApplication {

    private Geometry sphereGeom = null;
    private float angle = 0;
    private final Quaternion q = new Quaternion();
    private final Quaternion rot = new Quaternion();

    public static void main(String[] args) {
        MapTest app = new MapTest();
        app.start();
    }

    @Override
    public void simpleInitApp() {
        showGlobe();
        //showMap();
        //test2();
    }

    public void test2() {
        cam.setLocation(new Vector3f(3, 3, 3));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

        //setup main scene
        Texture offTex = renderHeightmap();
        Image image = offTex.getImage();

        // 2. Créer une heightmap
        ImageBasedHeightMap heightMap = new ImageBasedHeightMap(image);
        heightMap.load();

        // 3. Construire le terrain
        int patchSize = 65; // doit être une puissance de 2 + 1
        int terrainSize = 512;

        TerrainQuad terrain = new TerrainQuad("Generated Terrain", patchSize, terrainSize + 1, heightMap.getHeightMap());

        // Optionnel : ajouter un material
        Material terrainMat = assetManager.loadMaterial("Shaders/map-color.j3m");
        terrain.setMaterial(terrainMat);

        // Positionner et ajouter à la scène
        terrain.setLocalTranslation(0, 0, 0);
        terrain.setLocalScale(1, 100, 1); // échelle en hauteur (Y)
        rootNode.attachChild(terrain);
    }

    public void showGlobe() {
        rot.fromAngles(FastMath.HALF_PI, 0, 0);

        Texture heightmap = renderHeightmap();
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setTexture("ColorMap", heightmap);

        // 6. Créer une sphère avec cette texture
        Sphere sphere = new Sphere(32, 32, 2f);
        sphereGeom = new Geometry("TexturedSphere", sphere);
        sphereGeom.setMaterial(mat);
        q.fromAngles(FastMath.HALF_PI, 0, 0);
        sphereGeom.setLocalRotation(q);

        rootNode.attachChild(sphereGeom);
        cam.setLocation(new Vector3f(0, 0, -4));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        stateManager.detach(stateManager.getState(FlyCamAppState.class));
    }

    public void showMap() {
        GuiGlobals.initialize(this);

        Quad quad = new Quad(cam.getWidth() / 6.0f, cam.getHeight() / 6.0f);
        Geometry geo = new Geometry("HeightmapQuad", quad);
        Material heightmapMat = assetManager.loadMaterial("Shaders/map-color.j3m");
        geo.setMaterial(heightmapMat);
        geo.setLocalTranslation(cam.getWidth() * 5.0f / 6.0f, 0, 0);

        guiNode.attachChild(geo);
    }

    public Texture renderHeightmap() {
        Camera offCamera = new Camera(512, 512);

        ViewPort offView = renderManager.createPreView("Offscreen View", offCamera);
        offView.setClearFlags(true, true, true);
        offView.setBackgroundColor(ColorRGBA.Red);

        // create offscreen framebuffer
        FrameBuffer offBuffer = new FrameBuffer(offCamera.getWidth(), offCamera.getHeight(), 1);

        //setup framebuffer's cam
        offCamera.setFrustumPerspective(45f, 1f, 1f, 1000f);
        offCamera.setLocation(new Vector3f(0f, 0f, -3f));
        offCamera.lookAt(new Vector3f(0f, 0f, 0f), Vector3f.UNIT_Y);

        //setup framebuffer's texture
        Texture2D offTex = new Texture2D(offBuffer.getWidth(), offBuffer.getHeight(), Format.RGBA8);
        offTex.setMinFilter(Texture.MinFilter.Trilinear);
        offTex.setMagFilter(Texture.MagFilter.Bilinear);

        //setup framebuffer to use texture
        offBuffer.setDepthTarget(FrameBufferTarget.newTarget(Format.Depth));
        offBuffer.addColorTarget(FrameBufferTarget.newTarget(offTex));

        //set viewport to render to offscreen framebuffer
        offView.setOutputFrameBuffer(offBuffer);

        // setup framebuffer's scene
        Box boxMesh = new Box(1, 1, 1);
        //Quad quad = new Quad(cam.getWidth(), cam.getHeight());
        Material material = assetManager.loadMaterial("Shaders/map-grayscale.j3m");
        Geometry offBox = new Geometry("box", boxMesh);
        offBox.setMaterial(material);

        // attach the scene to the viewport to be rendered
        offView.attachScene(offBox);
        offBox.updateGeometricState();

        return offTex;
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (sphereGeom != null) {
            angle += tpf / 2;
            angle %= FastMath.TWO_PI;
            q.fromAngles(0, 0, -angle);
            sphereGeom.setLocalRotation(rot.mult(q));
        }
    }

    @Override
    public void simpleRender(RenderManager rm) {
        // Rien à faire ici pour l’instant
    }

}
