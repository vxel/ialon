/**
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

package org.delaunois.ialon.serialize;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

import org.delaunois.ialon.Config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A POJO for deserializing player state from file.
 *
 * @author Cedric de Launois
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlayerStateDTO {

    private float posx;
    private float posy;
    private float posz;

    private float rotx;
    private float roty;
    private float rotz;
    private float rotw;

    private float time = FastMath.HALF_PI;
    private int timeFactorIndex = 1;
    private int gridRadius = Config.GRID_RADIUS;
    private boolean fly = Config.PLAYER_START_FLY;

    public PlayerStateDTO(Vector3f location, Quaternion rotation, float time) {
        this.posx = location.x;
        this.posy = location.y;
        this.posz = location.z;
        if (rotation != null) {
            this.rotx = rotation.getX();
            this.roty = rotation.getY();
            this.rotz = rotation.getZ();
            this.rotw = rotation.getW();
        }
        this.time = time;
    }

    @JsonIgnore
    public Vector3f getLocation() {
        return new Vector3f(posx, posy, posz);
    }

    @JsonIgnore
    public Quaternion getRotation() {
        return new Quaternion(rotx, roty, rotz, rotw);
    }
}
