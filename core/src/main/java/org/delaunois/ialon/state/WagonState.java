package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.material.Material;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;

import org.delaunois.ialon.Ialon;

import lombok.Getter;

public class WagonState extends BaseAppState {

    private Node rootNode;
    private Ialon app;

    @Getter
    private Node wagonNode;

    private final Quaternion rotation = new Quaternion();
    private float angle = 0;

    @Override
    protected void initialize(Application app) {
        this.app = (Ialon) app;
        this.rootNode = this.app.getRootNode();

        wagonNode = new Node("Wagon");
        Geometry wagon = createWagon();
        wagon.setLocalTranslation(0, -0.2f, 0);
        wagonNode.attachChild(wagon);
        BoxCollisionShape collisionShape = new BoxCollisionShape(0.25f, 0.15f, 0.35f);
        RigidBodyControl rigidBodyControl = new RigidBodyControl(collisionShape, 1f);
        wagonNode.addControl(rigidBodyControl);
        rigidBodyControl.setKinematic(true);
        wagonNode.setLocalTranslation(-15.5f, 61f, -66.5f);
    }

    @Override
    public void update(float tpf) {
        // Nothing to do
        angle += tpf;
        //wagonNode.getLocalTranslation().addLocal(0, tpf * 0.1f, 0);
        wagonNode.setLocalRotation(rotation.fromAngleNormalAxis(angle, Vector3f.UNIT_Y));
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        if (wagonNode.getParent() == null) {
            BulletAppState bulletAppState = app.getStateManager().getState(BulletAppState.class);
            bulletAppState.getPhysicsSpace().add(wagonNode);
            //bulletAppState.setDebugEnabled(true);
            rootNode.attachChild(wagonNode);
        }
    }

    @Override
    protected void onDisable() {
        if (wagonNode.getParent() != null) {
            wagonNode.getParent().detachChild(wagonNode);
            BulletAppState bulletAppState = app.getStateManager().getState(BulletAppState.class);
            if (bulletAppState.getPhysicsSpace() != null) {
                bulletAppState.getPhysicsSpace().remove(wagonNode);
            }
        }
    }

    private Geometry createWagon() {
        Geometry model = (Geometry) app.getAssetManager().loadModel("Models/Wagon/wagon.j3o");
        Material modelMaterial = model.getMaterial();
        modelMaterial.setTexture("DiffuseMap", app.getConfig().getTextureAtlasManager().getDiffuseMap());
        return model;
    }
}
