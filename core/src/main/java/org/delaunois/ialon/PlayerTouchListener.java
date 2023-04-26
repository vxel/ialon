/*
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon;

import com.jme3.input.InputManager;
import com.jme3.input.TouchInput;
import com.jme3.input.controls.TouchListener;
import com.jme3.input.controls.TouchTrigger;
import com.jme3.input.event.TouchEvent;
import com.jme3.math.Vector3f;

import org.delaunois.ialon.state.PlayerState;

public class PlayerTouchListener implements TouchListener {

    private static final String TOUCH_MAPPING = "touch";

    private final CameraHelper cameraHelper;
    private final PlayerState playerState;
    private final InputManager inputManager;

    public PlayerTouchListener(PlayerState playerState, IalonConfig config) {
        this.playerState = playerState;
        this.inputManager = playerState.getApplication().getInputManager();
        this.cameraHelper = new CameraHelper(config);
    }

    @Override
    public void onTouch(String name, TouchEvent event, float tpf) {
        if (playerState.isTouchEnabled() && event.getType() == TouchEvent.Type.MOVE) {
            Vector3f point = new Vector3f(event.getX(), event.getY(), 1);
            if (!playerState.getPlayerActionButtons().getDirectionButtons().getWorldBound()
                    .intersects(point)

                    && !playerState.getPlayerActionButtons().getButtonAddBlock().getWorldBound()
                    .merge(playerState.getPlayerActionButtons().getButtonJump().getWorldBound())
                    .intersects(point)

                    && !playerState.getPlayerActionButtons().getButtonRemoveBlock().getWorldBound()
                    .merge(playerState.getPlayerActionButtons().getButtonFly().getWorldBound())
                    .intersects(point)

                    && event.getY() > 130
            ) {
                cameraHelper.rotate(playerState.getCamera(), -event.getDeltaX() / 400);
                cameraHelper.rotate(playerState.getCamera(), -event.getDeltaY() / 400, playerState.getCamera().getLeft());
            }
            event.setConsumed();
        }
    }

    public void addKeyMappings() {
        inputManager.addMapping(TOUCH_MAPPING, new TouchTrigger(TouchInput.ALL));
        inputManager.addListener(this, TOUCH_MAPPING);
    }

    public void deleteKeyMappings() {
        inputManager.deleteMapping(TOUCH_MAPPING);
        inputManager.removeListener(this);
    }

}
