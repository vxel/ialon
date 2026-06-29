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

package org.delaunois.ialon.state;

import com.jme3.app.state.AppState;
import com.jme3.app.state.AppStateManager;
import com.simsilica.mathd.Vec3i;

import java.util.Arrays;
import java.util.List;

/**
 * A modal "block picking" interaction where the player aims at world blocks and confirms a selection
 * with the primary input (left-click on desktop, the on-screen action button on mobile). Implemented by
 * the creation-capture mode ({@link CreationCaptureState}) and the creation-placement mode
 * ({@link CreationPlacementState}), which are mutually exclusive.
 *
 * <p>Centralising the contract lets {@code PlaceholderControl} (live aim + action-button visibility),
 * {@code PlayerActionControl} (confirm) and {@link TimeFactorState} (UI hiding) treat both modes the
 * same way, via {@link #active(AppStateManager)}, instead of knowing each one.</p>
 *
 * @author Cedric de Launois
 */
public interface BlockPickingMode {

    /** The implementing states, in priority order, that {@link #active} scans. */
    List<Class<? extends AppState>> MODES = Arrays.asList(
            CreationCaptureState.class, CreationPlacementState.class);

    /** Whether this mode is currently active (the player is picking blocks). */
    boolean isPicking();

    /** The block currently aimed at (or {@code null} when nothing is targeted), pushed each aim tick. */
    void onTarget(Vec3i cell);

    /**
     * A block was confirmed (left-click / action button). {@code cell} is the aimed block, or {@code null}
     * if nothing is aimed. Capture selects the aimed block ; placement stamps the creation.
     */
    void onPick(Vec3i cell);

    /** Aborts the mode without changing the world (right-click on desktop). */
    void onCancel();

    /** Returns the single active picking mode, or {@code null} if none is. */
    static BlockPickingMode active(AppStateManager stateManager) {
        for (Class<? extends AppState> type : MODES) {
            AppState state = stateManager.getState(type);
            if (state instanceof BlockPickingMode && ((BlockPickingMode) state).isPicking()) {
                return (BlockPickingMode) state;
            }
        }
        return null;
    }
}
