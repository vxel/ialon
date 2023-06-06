package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.debug.Arrow;

public class AxesDebugState extends BaseAppState {

    private static final int SIZE = 50;

    private final Node axes;
    private final Arrow x;
    private final Arrow y;
    private final Arrow z;

    private Node rootNode;
    private SimpleApplication app;

    public AxesDebugState() {
        x = new Arrow(Vector3f.UNIT_X.mult(SIZE));
        y = new Arrow(Vector3f.UNIT_Y.mult(SIZE));
        z = new Arrow(Vector3f.UNIT_Z.mult(SIZE));
        axes = new Node("Axes");
    }

    @Override
    protected void initialize(Application app) {
        this.app = (SimpleApplication) app;
        this.rootNode = this.app.getGuiNode();
        Geometry gx = buildGeometry(x, ColorRGBA.Red);
        Geometry gy = buildGeometry(y, ColorRGBA.Green);
        Geometry gz = buildGeometry(z, ColorRGBA.Blue);
        axes.attachChild(gx);
        axes.attachChild(gy);
        axes.attachChild(gz);
        Vector3f position = new Vector3f(50.0f, app.getCamera().getHeight() * 0.38f, -1);
        setPosition(position);
    }

    @Override
    public void update(float tpf) {
        axes.setLocalRotation(app.getCamera().getRotation());
    }

    @Override
    protected void cleanup(Application app) {
    }

    public void setPosition(Vector3f position) {
        axes.setLocalTranslation(position);
    }

    @Override
    protected void onEnable() {
        if (rootNode == null) {
            rootNode = ((SimpleApplication) getApplication()).getGuiNode();
        }

        if (axes.getParent() == null) {
            rootNode.attachChild(axes);
        }
    }

    @Override
    protected void onDisable() {
        if (axes.getParent() != null) {
            rootNode.detachChild(axes);
        }
    }

    private Geometry buildGeometry(Mesh shape, ColorRGBA color) {
        Geometry g = new Geometry("coordinate axis", shape);
        Material mat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mat.getAdditionalRenderState().setWireframe(true);
        mat.getAdditionalRenderState().setLineWidth(4);
        mat.setColor("Color", color);
        g.setMaterial(mat);
        return g;
    }
}
