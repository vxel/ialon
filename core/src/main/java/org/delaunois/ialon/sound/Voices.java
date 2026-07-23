/**
 * Copyright (C) 2022 Cedric de Launois
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

package org.delaunois.ialon.sound;

import com.jme3.asset.AssetManager;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioKey;
import com.jme3.audio.AudioNode;
import com.jme3.audio.AudioSource;
import com.jme3.math.Vector3f;

/**
 * The playing voices of one clip bank, and the reason AmbientSoundState does not use
 * {@link com.jme3.audio.AudioNode#playInstance()}.
 *
 * playInstance is the obvious way to fire a one-shot, and it was used here at first, but it hands
 * the clip to a transient OpenAL channel and returns nothing : there is no handle, and neither
 * {@code setVolume} nor {@code stop} on the node reaches an instance already in flight. So a bird
 * that started a moment before the player dived kept singing under water for its remaining seconds.
 * jME offers no way to reach those channels — {@code stopSource} only knows about a source's own.
 *
 * So each clip gets a few AudioNodes of its own, all sharing that clip's decoded buffer (the
 * {@code AudioData} is passed to the constructor, not re-loaded, so this costs objects and no PCM),
 * played with {@code play()} and kept. They are then ours to fade. {@link #PER_CLIP} of them because
 * a clip can overlap itself across layers ; the cursor cycles so the oldest is the one reused.
 */
public final class Voices {

    private static final int PER_CLIP = 3;

    /** OpenAL inverse-distance attenuation : full volume up to here, then 1/d out to MAX_DISTANCE. */
    private static final float REF_DISTANCE = 10f;
    private static final float MAX_DISTANCE = 80f;

    private final AudioNode[] nodes;
    /** The randomised gain each voice was triggered at, before the gate. */
    private final float[] gains;
    private final int[] cursor;
    private final int clipCount;

    public Voices(AssetManager assetManager, String pattern, int clipCount) {
        this.clipCount = clipCount;
        this.nodes = new AudioNode[clipCount * PER_CLIP];
        this.gains = new float[clipCount * PER_CLIP];
        this.cursor = new int[clipCount];
        for (int c = 0; c < clipCount; c++) {
            String path = String.format(pattern, c + 1);
            // Buffer, not Stream : these are ~2 s one-shots, and a stream cannot be positioned
            // cheaply nor restarted this often.
            AudioKey key = new AudioKey(path, false);
            AudioData data = assetManager.loadAudio(key);
            for (int s = 0; s < PER_CLIP; s++) {
                AudioNode node = new AudioNode(data, key);
                node.setName(path);
                node.setPositional(true);
                node.setReverbEnabled(false);
                node.setRefDistance(REF_DISTANCE);
                node.setMaxDistance(MAX_DISTANCE);
                nodes[c * PER_CLIP + s] = node;
            }
        }
    }

    public int clipCount() {
        return clipCount;
    }

    public void trigger(int clip, float gain, float pitch, Vector3f at, float currentGate) {
        int i = clip * PER_CLIP + cursor[clip];
        cursor[clip] = (cursor[clip] + 1) % PER_CLIP;
        AudioNode node = nodes[i];
        node.setLocalTranslation(at);
        // Not attached to the scene graph on purpose : with no parent this copies the local
        // transform into the world one, which is what the renderer reads. See the class javadoc.
        node.updateGeometricState();
        gains[i] = gain;
        node.setVolume(gain * currentGate);
        node.setPitch(pitch);
        node.play();
    }

    /** Re-applies the gate to everything currently sounding. */
    public void applyGate(float currentGate) {
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i].getStatus() == AudioSource.Status.Playing) {
                nodes[i].setVolume(gains[i] * currentGate);
            }
        }
    }

    public void stopAll() {
        for (AudioNode node : nodes) {
            if (node.getStatus() != AudioSource.Status.Stopped) {
                node.stop();
            }
        }
    }
}
