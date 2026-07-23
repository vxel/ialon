package org.delaunois.ialon.blocks;

import com.jme3.scene.Node;

/**
 * The node of a chunk that renders nothing : either an empty (air) chunk, or a chunk skipped at mesh
 * time because none of its faces can be seen. It is kept out of the scene graph, but still carries the
 * chunk's face-connectivity as userData so the renderer's cave-culling visibility graph knows whether
 * sight passes through it (air) or stops at it (solid opaque rock).
 */
public class EmptyNode extends Node {

    /** An empty node carrying the given face-connectivity bitset for the cave-culling BFS. */
    static EmptyNode withConnectivity(short connectivity) {
        EmptyNode node = new EmptyNode();
        node.setUserData(Chunk.USERDATA_FACE_CONNECTIVITY, connectivity & 0xFFFF);
        return node;
    }
}
