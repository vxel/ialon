package org.delaunois.ialon;

import com.jme3.input.controls.TouchListener;
import com.jme3.input.event.TouchEvent;
import com.jme3.math.Vector3f;

import org.delaunois.ialon.state.PlayerState;

public class PlayerTouchListener implements TouchListener {

    private final PlayerState playerState;

    public PlayerTouchListener(PlayerState playerState) {
        this.playerState = playerState;
    }

    @Override
    public void onTouch(String name, TouchEvent event, float tpf) {
        if (playerState.isTouchEnabled() && event.getType() == TouchEvent.Type.MOVE) {
            Vector3f point = new Vector3f(event.getX(), event.getY(), 1);
            if (!playerState.getDirectionButtons().getWorldBound()
                    .intersects(point)

                    && !playerState.getButtonAddBlock().getWorldBound().merge(playerState.getButtonJump().getWorldBound())
                    .intersects(point)

                    && !playerState.getButtonRemoveBlock().getWorldBound().merge(playerState.getButtonFly().getWorldBound())
                    .intersects(point)

                    && event.getY() > 130
            ) {
                CameraHelper.rotate(playerState.getCamera(), -event.getDeltaX() / 400);
                CameraHelper.rotate(playerState.getCamera(), -event.getDeltaY() / 400, playerState.getCamera().getLeft());
            }
            event.setConsumed();
        }
    }

}
