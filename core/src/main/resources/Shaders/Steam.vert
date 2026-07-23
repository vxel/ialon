#import "Common/ShaderLib/GLSLCompat.glsllib"

// Backdrop quad of a full-screen popup, drawn by the GUI (orthographic) camera : nothing to do but pass
// the quad's own UV down, which is where Steam.frag gets its pixel coordinates from. Using the quad UV
// rather than gl_FragCoord keeps the plume anchored to the POPUP (and thus to its frame's bottom pipe)
// instead of to the window, which is what makes the leak line up with the tube at any resolution.

uniform mat4 g_WorldViewProjectionMatrix;

attribute vec3 inPosition;
attribute vec2 inTexCoord;

// 0..1 over the quad, origin at the BOTTOM-LEFT (jME Quad convention) : y grows upwards, like the steam.
varying vec2 vUv;

void main() {
    vUv = inTexCoord;
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
}
