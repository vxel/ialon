/**
 * Ialon, a block construction game
 * Copyright (C) 2022 Cédric de Launois
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.delaunois.ialon.state;

/**
 * A dismissable UI overlay (modal menu, settings panel, minimap, block picker...). Implemented by the
 * AppStates that own such an overlay so a single ESC handler in {@code Ialon} can close whichever popup
 * is currently open instead of quitting the game.
 *
 * @author Cedric de Launois
 */
public interface Popup {

    /** @return {@code true} if this popup is currently displayed. */
    boolean isPopupOpen();

    /** Closes this popup if it is open ; a no-op otherwise. */
    void closePopup();

    /**
     * Whether this popup, while open, hides the world completely — a full-screen panel wearing the opaque
     * veil ({@link org.delaunois.ialon.ui.IalonTheme#applyVeil}). The world is then <b>neither drawn nor
     * heard</b> while it is up (see {@code Ialon.updateWorldPresence}) : the 3D scene is culled, and the
     * ambient wildlife and water bed are stopped.
     *
     * <p>That is the whole point of stating this. Nothing in jME links "covered by the GUI" to "culled", so a
     * full world — chunks, far terrain, sky — otherwise keeps being drawn behind the curtain for nobody, at
     * full cost in draw calls, battery and heat ; and birds carrying on over a full-screen menu is the world
     * the player has just stepped out of still going on around them.
     *
     * <p>Defaults to {@code false}, which is the safe answer : a HUD overlay (the block picker), an in-world
     * mode (the region selection), a modal wearing the clear veil, or any popup the world shows through must
     * keep the world drawn — and audible, since the player is still standing in it.
     *
     * @return {@code true} if the world can stop being rendered and heard while this popup is open
     */
    default boolean hidesWorld() {
        return false;
    }
}
