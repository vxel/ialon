package org.delaunois.ialon.control;

import com.jme3.bullet.collision.shapes.ConvexShape;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.TypeIds;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.WorldManager;

import lombok.Getter;

@Getter
public class PlayerControl extends CharacterControl {

    @Getter
    private final Vector3f oldPlayerBlockCenterLocation = new Vector3f();

    @Getter
    private final Vector3f playerBlockCenterLocation = new Vector3f();

    @Getter
    private final Vector3f walkDirection = new Vector3f();

    private final WorldManager worldManager;
    private final Vector3f playerLocation = new Vector3f();
    private final IalonConfig config;

    private Block block;
    private boolean underWater = false;
    private boolean onScale = false;

    public PlayerControl(ConvexShape shape, float stepHeight, WorldManager worldManager, IalonConfig config) {
        super(shape, stepHeight);
        this.worldManager = worldManager;
        this.config = config;
        setGravity(config.getGroundGravity());
    }

    public void update(float tpf) {
        super.update(tpf);
        walkDirection.zero();

        playerLocation.set(getSpatial().getWorldTranslation());
        config.setPlayerLocation(playerLocation);

        if (playerLocation.y < 1) {
            playerLocation.setY(config.getMaxy());
            getSpatial().setLocalTranslation(playerLocation);
        }

        oldPlayerBlockCenterLocation.set(playerBlockCenterLocation);
        playerBlockCenterLocation.set(
                (int)playerLocation.x + 0.5f * Math.signum(playerLocation.x),
                (int)playerLocation.y,
                (int)playerLocation.z + 0.5f * Math.signum(playerLocation.z));

        block = worldManager.getBlock(playerBlockCenterLocation);

        if (block != null) {
            if (block.getName().contains("water")) {
                underWater = true;

            } else if (TypeIds.SCALE.equals(block.getType())) {
                onScale = true;
            }

        } else if (underWater) {
            underWater = false;

        } else if (onScale) {
            onScale = false;
        }
    }

    @Override
    public void setWalkDirection(Vector3f offset) {
        walkDirection.set(offset);
        super.setWalkDirection(offset);
    }
}
