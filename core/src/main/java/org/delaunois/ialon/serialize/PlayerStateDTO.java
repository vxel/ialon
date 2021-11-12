package org.delaunois.ialon.serialize;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

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
