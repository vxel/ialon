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

import com.jme3.input.controls.TouchListener;
import com.jme3.input.event.TouchEvent;
import com.jme3.math.Vector3f;

import org.delaunois.ialon.state.PlayerState;

public class PlayerTouchListener implements TouchListener {

    private final PlayerState playerState;
    private final CameraHelper cameraHelper;

    public PlayerTouchListener(PlayerState playerState, IalonConfig config) {
        this.playerState = playerState;
        this.cameraHelper = new CameraHelper(config);
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
                cameraHelper.rotate(playerState.getCamera(), -event.getDeltaX() / 400);
                cameraHelper.rotate(playerState.getCamera(), -event.getDeltaY() / 400, playerState.getCamera().getLeft());
            }
            event.setConsumed();
        }
    }

}
