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

package org.delaunois.ialon.control;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.terrain.Terrain;
import com.jme3.terrain.geomipmap.TerrainLodControl;
import com.jme3.terrain.geomipmap.UpdatedTerrainPatch;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import lombok.extern.slf4j.Slf4j;

/**
 * A {@link TerrainLodControl} that only recomputes the terrain LOD when the camera has moved at least
 * {@code minMoveDistance} world units since the last recompute, instead of on every camera movement.
 *
 * <p>The stock control runs its LOD pass (which, on the main thread, caches the quad transforms and then
 * applies the per-patch index-buffer rebuilds returned by the worker) every frame the camera moves. On
 * the far-terrain {@code TerrainQuad} that showed up as intermittent multi-ms {@code updateLogicalState}
 * hitches while the player moves — Controls run inside {@code rootNode.updateLogicalState()}. The far
 * terrain is a distant horizon backdrop whose patch-to-camera distances change very slowly, so its LOD
 * needs refreshing only every so many units of travel ; this caps how often that cost is paid.
 *
 * <p>LOD is recomputed once on the first update (so the terrain starts at the right detail), then only
 * after each {@code minMoveDistance} of camera travel. Between those points the patches keep their last
 * LOD — imperceptible on a far backdrop.
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ThrottledTerrainLodControl extends TerrainLodControl {

    // Far horizon : LOD only needs refreshing every several chunks of travel. The recompute applies
    // per-patch index-buffer rebuilds on the main thread (a multi-ms updateLogicalState spike on the
    // big far TerrainQuad), so refreshing rarely matters more than the slight LOD staleness — which is
    // imperceptible on a distant backdrop. Tunable per instance.
    public static final float DEFAULT_MIN_MOVE = 128f;

    private final Camera throttleCamera;
    private final float minMoveSq;
    private final Vector3f lastUpdatePos = new Vector3f();
    private boolean firstDone;

    public ThrottledTerrainLodControl(Terrain terrain, Camera camera) {
        this(terrain, camera, DEFAULT_MIN_MOVE);
    }

    public ThrottledTerrainLodControl(Terrain terrain, Camera camera, float minMoveDistance) {
        super(terrain, camera);
        this.throttleCamera = camera;
        this.minMoveSq = minMoveDistance * minMoveDistance;
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (throttleCamera != null) {
            Vector3f loc = throttleCamera.getLocation();
            if (firstDone && loc.distanceSquared(lastUpdatePos) < minMoveSq) {
                // Not moved far enough to justify a new LOD computation -- but the PREVIOUS one may have
                // finished and still be waiting to be applied, so we cannot simply return.
                applyPendingLod();
                return;
            }
            lastUpdatePos.set(loc);
            firstDone = true;
        }
        super.controlUpdate(tpf);
    }

    /**
     * Applies a finished LOD computation to the patches, if one is waiting.
     *
     * <p>{@link TerrainLodControl} splits the work in two : the computation runs on a background thread,
     * and its result is applied to the patch index buffers at the START of the NEXT {@code updateLOD} call
     * (jME's own {@code updateQuadLODs}, which is private and reachable no other way). Throttling by
     * returning before {@code super.controlUpdate} therefore suppressed the apply along with the
     * recompute : the terrain kept its old index buffers until the camera had travelled a full
     * {@code minMoveDistance}, and for a camera that never moves — a screenshot harness, a player standing
     * still — the very first computation was stranded and no LOD was <b>ever</b> applied.
     *
     * <p>So we do the apply ourselves here, without going through {@code updateLOD}, which is what keeps
     * the throttle honest : no new computation is ever submitted while throttled.
     */
    private void applyPendingLod() {
        if (indexer == null || !indexer.isDone()) {
            return;
        }
        try {
            Map<String, UpdatedTerrainPatch> updated = indexer.get();
            if (updated != null) {
                for (UpdatedTerrainPatch utp : updated.values()) {
                    utp.updateAll();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("Far terrain LOD computation failed", e);
        } finally {
            indexer = null;
        }
    }
}
