package org.delaunois.ialon.blocks.shapes;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import org.delaunois.ialon.blocks.ChunkMesh;
import org.delaunois.ialon.blocks.Direction;
import org.delaunois.ialon.blocks.Shape;
import com.simsilica.mathd.Vec3i;

/**
 * A door panel : geometrically identical to a thin vertical {@link Slab} (a Plate flush against one
 * cell face), but its two large faces are always textured <b>upright</b>, in every orientation.
 * <p>
 * A plain Slab ties each face's UVs to the rotated local frame. The four door orientations are built
 * with rotations around different axes (NORTH/SOUTH around X, EAST/WEST around Z), so the texture of
 * the big panel face ended up rotated by 90° when a door was opened (its plate swapped from a N/S plane
 * to an E/W plane). Here the big-face UVs are recomputed from <b>world space</b> — U along the panel's
 * horizontal width, V along world height — so the texture stays upright whether the door is open or
 * closed. Everything else (geometry, collision, {@link #fullyCoversFace}, lighting) is inherited from
 * {@link Slab} unchanged. Single-image only (doors are mono-textured).
 */
public class Door extends Slab {

    // Big-face vertices, in the exact emission order Slab#createUp / Slab#createDown use, and the UVs
    // precomputed once (direction is fixed per instance, so the UVs never change).
    private final Vector3f[] cornersUp;
    private final Vector3f[] cornersDown;
    private final Vector2f[] uvUp;
    private final Vector2f[] uvDown;

    public Door(float startY, float endY, Direction direction) {
        super(startY, endY, direction);
        // this.startY / this.endY are the Slab-stored values (input shifted by -0.5).
        cornersUp = new Vector3f[]{
                new Vector3f(0.5f, this.endY, -0.5f),   // tPN
                new Vector3f(-0.5f, this.endY, -0.5f),  // tNN
                new Vector3f(0.5f, this.endY, 0.5f),    // tPP
                new Vector3f(-0.5f, this.endY, 0.5f)    // tNP
        };
        cornersDown = new Vector3f[]{
                new Vector3f(-0.5f, this.startY, -0.5f), // bNN
                new Vector3f(0.5f, this.startY, -0.5f),  // bPN
                new Vector3f(-0.5f, this.startY, 0.5f),  // bNP
                new Vector3f(0.5f, this.startY, 0.5f)    // bPP
        };
        uvUp = uprightFaceUvs(cornersUp);
        uvDown = uprightFaceUvs(cornersDown);

        // The plate is rotated to face its direction. Facing a NEGATIVE axis (NORTH = -Z, WEST = -X)
        // the world-space U/V mapping above already reads upright ; facing a POSITIVE axis
        // (EAST = +X, SOUTH = +Z) the panel comes out turned 180°. So flip exactly the positive-axis
        // orientations, which is simply: the non-zero component of the facing vector is +1.
        Vec3i facing = direction.getVector();
        if (facing.x + facing.z > 0) {
            rotate180(uvUp);
            rotate180(uvDown);
        }
    }

    /** Rotates a face's 4 UVs by 180° in-place (texture upside-down + mirrored). */
    private static void rotate180(Vector2f[] uvs) {
        for (Vector2f uv : uvs) {
            // The UVs sit in the padded range [PAD, 1-PAD], symmetric around 0.5, so 1 - c mirrors within it.
            uv.set(1f - uv.x, 1f - uv.y);
        }
    }

    /**
     * Builds the upright UVs for a big face : U runs along the face's horizontal width (whichever of
     * the world X/Z axes actually spans the panel), V runs along world height (Y). Independent of which
     * rotation axis produced the orientation, so all four orientations look identical.
     */
    private Vector2f[] uprightFaceUvs(Vector3f[] corners) {
        Quaternion rot = emitRotation; // non-null for the N/S/E/W door orientations
        Vector3f[] world = new Vector3f[corners.length];
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < corners.length; i++) {
            world[i] = rot == null ? corners[i].clone() : rot.mult(corners[i]);
            minX = Math.min(minX, world[i].x);
            maxX = Math.max(maxX, world[i].x);
            minZ = Math.min(minZ, world[i].z);
            maxZ = Math.max(maxZ, world[i].z);
            minY = Math.min(minY, world[i].y);
            maxY = Math.max(maxY, world[i].y);
        }

        boolean widthIsX = (maxX - minX) >= (maxZ - minZ);
        float hMin = widthIsX ? minX : minZ;
        float hSpan = widthIsX ? (maxX - minX) : (maxZ - minZ);
        float ySpan = maxY - minY;
        float pad = Shape.UV_PADDING;
        float inner = 1f - 2f * pad;

        Vector2f[] uvs = new Vector2f[corners.length];
        for (int i = 0; i < corners.length; i++) {
            float h = widthIsX ? world[i].x : world[i].z;
            float u = pad + (hSpan == 0 ? 0 : (h - hMin) / hSpan) * inner;
            float v = pad + (ySpan == 0 ? 0 : (world[i].y - minY) / ySpan) * inner;
            uvs[i] = new Vector2f(u, v);
        }
        return uvs;
    }

    @Override
    protected void createUp(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                cornersUp[0], cornersUp[1], cornersUp[2], cornersUp[3],
                nUp, uvUp, false, chunkMesh.isCollisionMesh());
    }

    @Override
    protected void createDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                cornersDown[0], cornersDown[1], cornersDown[2], cornersDown[3],
                nDown, uvDown, false, chunkMesh.isCollisionMesh());
    }
}
