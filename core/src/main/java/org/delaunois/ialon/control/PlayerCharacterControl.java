package org.delaunois.ialon.control;

import com.jme3.bullet.collision.shapes.ConvexShape;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.TypeIds;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.WorldManager;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class PlayerCharacterControl extends CharacterControl {

    private final Vector3f oldPlayerBlockCenterLocation = new Vector3f();
    private final Vector3f playerBlockCenterLocation = new Vector3f();
    private final Vector3f walkDirection = new Vector3f();

    private final WorldManager worldManager;
    private final Vector3f playerLocation = new Vector3f();
    private final IalonConfig config;

    private Block block;
    private boolean underWater = false;
    private boolean onScale = false;
    private CharacterControl characterControl;

    public PlayerCharacterControl(ConvexShape shape, float stepHeight, WorldManager worldManager, IalonConfig config) {
        super(shape, stepHeight);
        this.worldManager = worldManager;
        this.config = config;
        setGravity(config.getGroundGravity());
    }

    @Override
    public void setSpatial(Spatial newSpatial) {
        super.setSpatial(newSpatial);
        characterControl = newSpatial.getControl(CharacterControl.class);
        if (characterControl == null) {
            throw new IllegalStateException("PlayerCharacterControl needs a spatial with a CharacterControl");
        }
    }

    @Override
    public void update(float tpf) {
        super.update(tpf);
        walkDirection.zero();

        playerLocation.set(getSpatial().getLocalTranslation());
        config.setPlayerLocation(playerLocation);

        if (playerLocation.y < 1) {
            playerLocation.setY(config.getMaxy());
            characterControl.setPhysicsLocation(playerLocation);
        }

        oldPlayerBlockCenterLocation.set(playerBlockCenterLocation);
        playerBlockCenterLocation.set(
                (int)playerLocation.x + 0.5f * Math.signum(playerLocation.x),
                (int)playerLocation.y + 0.5f * Math.signum(playerLocation.y),
                (int)playerLocation.z + 0.5f * Math.signum(playerLocation.z));

        block = worldManager.getBlock(playerBlockCenterLocation);

        if (block != null) {
            if (!underWater && block.getName().contains("water")) {
                log.info("In water");
                underWater = true;
                // In water, we don't need to climb stairs, just swim ;-)
                // Setting step height to a low value prevents a bug in bullet
                // that makes the character fall with a different speed below
                // the stepHeight. This bug is noticeable especially under water.
                getCharacter().setStepHeight(0.03f);
                setFallSpeed(config.getWaterGravity());
                setJumpSpeed(config.getWaterJumpSpeed());

            } else if (!onScale && TypeIds.SCALE.equals(block.getType())) {
                log.info("On scale");
                onScale = true;
                setFallSpeed(0);
            }

        } else if (underWater) {
            log.info("Out of water");
            underWater = false;
            setFallSpeed(config.getGroundGravity());
            setJumpSpeed(config.getJumpSpeed());
            getCharacter().setStepHeight(config.getPlayerStepHeight());

        } else if (onScale) {
            log.info("Out of scale");
            onScale = false;
            setFallSpeed(config.getGroundGravity());
        }
    }

    @Override
    public void setWalkDirection(Vector3f offset) {
        walkDirection.set(offset);
        super.setWalkDirection(offset);
    }
}
