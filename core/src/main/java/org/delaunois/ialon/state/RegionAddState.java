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
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.WorldManager;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * "Fill a whole region" : every <b>empty</b> cell inside the outlined box receives the currently
 * selected block. Cells already holding something are left alone, so a fill drapes itself around the
 * existing terrain and buildings instead of overwriting them.
 *
 * <p>Entered by holding the on-screen "+" (add block) button, see {@code PlayerActionControl}. The
 * preview is <b>green</b>, against the red of {@link RegionDeleteState} : the two share a gesture, so
 * the colour is what tells the player which way this one goes.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class RegionAddState extends RegionSelectionState {

    private static final ColorRGBA PREVIEW_COLOR = new ColorRGBA(0.3f, 1f, 0.4f, 0.25f);

    public RegionAddState(IalonConfig config) {
        super(config);
    }

    @Override
    protected ColorRGBA previewColor() {
        return PREVIEW_COLOR;
    }

    /** What the fill will place : the cells of the box that are still empty. */
    @Override
    protected int countAffectedCells() {
        int size = (boundsMax.x - boundsMin.x + 1)
                * (boundsMax.y - boundsMin.y + 1)
                * (boundsMax.z - boundsMin.z + 1);
        return size - countFilledCells();
    }

    @Override
    protected String confirmQuestion(int affected) {
        Block block = config.getSelectedBlock();
        if (block == null) {
            return "No block selected";
        }
        if (affected == 0) {
            return "No empty cell to fill here";
        }
        return "Fill " + affected + (affected == 1 ? " cell" : " cells") + " ?";
    }

    @Override
    protected String confirmButtonLabel() {
        return "Fill";
    }

    @Override
    protected String doneMessage(int affected) {
        return affected + (affected == 1 ? " block placed" : " blocks placed");
    }

    @Override
    protected void applyEdit(WorldManager worldManager, Vec3i min, Vec3i max) {
        Block block = config.getSelectedBlock();
        if (block == null) {
            log.warn("No block selected : region fill skipped");
            return;
        }
        worldManager.fillRegion(min, max, block, playerBlockLocation());
    }

    /**
     * The block cell the player stands in, so a solid fill can leave it (and the one above) free rather
     * than entombing them — the bulk counterpart of the guard in {@code PlayerActionControl#addBlock}.
     * {@code PlayerCharacterControl} refreshes this location every frame.
     */
    private Vec3i playerBlockLocation() {
        return Optional.ofNullable(config.getPlayerLocation())
                .map(ChunkManager::getBlockLocation)
                .orElse(null);
    }
}
