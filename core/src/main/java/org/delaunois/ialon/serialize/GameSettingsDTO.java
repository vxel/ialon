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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.delaunois.ialon.IalonConfig;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Global, world-independent GAME settings, persisted once in {@code save/game.yml}. These are shared
 * by every world (render distance, lighting, day/night speed) plus the id of the world to load on
 * startup. World-defining properties live in {@link WorldParams} and per-world player state in
 * {@link PlayerStateDTO}.
 *
 * @author Cedric de Launois
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GameSettingsDTO {

    private int gridRadius = 4;
    private Float ambiantIntensity;
    private Float sunIntensity;
    private int timeFactorIndex = 1;
    private String currentWorldId = IalonConfig.DEFAULT_WORLD_ID;

    public GameSettingsDTO(IalonConfig config) {
        this.gridRadius = config.getGridRadius();
        this.ambiantIntensity = config.getAmbiantIntensity();
        this.sunIntensity = config.getSunIntensity();
        this.timeFactorIndex = config.getTimeFactorIndex();
        this.currentWorldId = config.getWorldId();
    }

    public void applyTo(IalonConfig config) {
        config.setGridRadius(Math.max(3, gridRadius));
        if (ambiantIntensity != null) {
            config.setAmbiantIntensity(ambiantIntensity);
        }
        if (sunIntensity != null) {
            config.setSunIntensity(sunIntensity);
        }
        config.setTimeFactorIndex(timeFactorIndex);
    }
}
