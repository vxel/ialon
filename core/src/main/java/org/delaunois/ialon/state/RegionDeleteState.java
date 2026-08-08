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

import com.jme3.math.ColorRGBA;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.WorldManager;

/**
 * "Delete a whole region" : every block inside the outlined box is removed at once.
 *
 * <p>Entered by holding the on-screen "-" (remove block) button, see {@code PlayerActionControl}. The
 * preview is <b>red</b> : the gesture is shared with the other bulk modes, so the colour is what says
 * this one destroys. The confirmation before it runs is not optional — there is no undo in the game.</p>
 *
 * @author Cedric de Launois
 */
public class RegionDeleteState extends RegionSelectionState {

    private static final ColorRGBA PREVIEW_COLOR = new ColorRGBA(1f, 0.25f, 0.25f, 0.25f);

    public RegionDeleteState(IalonConfig config) {
        super(config);
    }

    @Override
    protected ColorRGBA previewColor() {
        return PREVIEW_COLOR;
    }

    /** What the deletion will remove : the cells actually holding a block. */
    @Override
    protected int countAffectedCells() {
        return countFilledCells();
    }

    @Override
    protected String confirmQuestion(int affected) {
        if (affected == 0) {
            return "Nothing to delete here";
        }
        return "Delete " + affected + (affected == 1 ? " block ?" : " blocks ?");
    }

    @Override
    protected String confirmButtonLabel() {
        return "Delete";
    }

    @Override
    protected String doneMessage(int affected) {
        return affected + (affected == 1 ? " block deleted" : " blocks deleted");
    }

    @Override
    protected void applyEdit(WorldManager worldManager, Vec3i min, Vec3i max) {
        worldManager.removeRegion(min, max);
    }
}
