package org.delaunois.ialon.blocks.shapes;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkMesh;
import org.delaunois.ialon.blocks.Shape;
import com.simsilica.mathd.Vec3i;

import lombok.ToString;

/**
 * A shape that emits a single, degenerate quad whose four vertices all sit at the block centre.
 * The corner of each vertex is carried in the UV ; a billboarding vertex shader (see
 * {@code Blocks/Shaders/Fire.vert}) expands the quad around that centre toward the camera, so the
 * face always looks at the player. This shape is therefore only meaningful with a billboard shader
 * — it is NOT a normal textured quad. Used by the fire block.
 *
 * @author Cedric de Launois
 */
@ToString
public class Billboard implements Shape {

    // Arbitrary normal : the billboard is emissive, the normal is unused by its shader.
    private static final Vector3f NORMAL = new Vector3f(0, 1, 0);

    // All four corners collapse to the block centre ; the shader rebuilds the quad from the UV.
    private static final Vector3f CENTER = new Vector3f(0, 0, 0);

    // Same padded-UV convention as CrossPlane (0.25 .. 0.75), so the fire fragment shader's
    // (uv - 0.25) * 2 remap is shared. The UV doubles as the corner index for the vertex shader.
    private static final Vector2f[] UVS = {
            new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING), // bottom-left
            new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING), // bottom-right
            new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING), // top-left
            new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING)  // top-right
    };

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        float blockScale = BlocksConfig.getInstance().getBlockScale();

        Shape.emitQuad(chunkMesh, location, blockScale, null,
                CENTER, CENTER, CENTER, CENTER,
                NORMAL, UVS, false, chunkMesh.isCollisionMesh());

        if (!chunkMesh.isCollisionMesh()) {
            Vector4f color = chunk.getLightLevel(location);
            chunkMesh.getColors().add(color);
            chunkMesh.getColors().add(color);
            chunkMesh.getColors().add(color);
            chunkMesh.getColors().add(color);
        }
    }

}
