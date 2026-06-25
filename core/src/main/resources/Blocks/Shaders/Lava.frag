#import "Common/ShaderLib/GLSLCompat.glsllib"

// Procedural molten-lava fragment shader.
// Direct port of the ShaderToy "lava" effect https://www.shadertoy.com/view/ssKBzm :
// a domain-warped fractal value-noise (fbm) whose
// reciprocal drives a dark-red -> bright-orange ramp, animated by the engine clock. The lava block
// is fully emissive (it is a light source, like fire), so there is no scene lighting here ; the
// computed colour is treated as linear and the sRGB encode (hardware on desktop, emulated under
// MANUAL_SRGB on Android GLES) brightens it for display — same convention as Fire.frag, so desktop
// and Android match.

uniform float g_Time;
uniform float m_Speed;

in vec2 vCoord; // world-space (seamless) or per-face UV (preview) coordinate, built in the vertex shader

mat2 rot(float a) {
    return mat2(cos(a), sin(a), -sin(a), cos(a));
}

float hash21(vec2 n) {
    return fract(cos(dot(n, vec2(5.9898, 4.1414))) * 65899.89956);
}

float noise(in vec2 n) {
    const vec2 d = vec2(0.0, 1.0);
    vec2 b = floor(n);
    vec2 f = smoothstep(vec2(0.0), vec2(1.0), fract(n));
    return mix(mix(hash21(b), hash21(b + d.yx), f.x), mix(hash21(b + d.xy), hash21(b + d.yy), f.x), f.y);
}

vec2 mixNoise(vec2 p) {
    float epsilon = 0.968785675;
    float noiseX = noise(vec2(p.x + epsilon, p.y)) - noise(vec2(p.x - epsilon, p.y));
    float noiseY = noise(vec2(p.x, p.y + epsilon)) - noise(vec2(p.x, p.y - epsilon));
    return vec2(noiseX, noiseY);
}

float fbm(in vec2 p) {
    float t = g_Time * 0.2 * m_Speed;
    float amplitude = 3.0;
    float total = 0.0;
    vec2 pom = p;
    // The ShaderToy original loops "for (float i = 1.3232; i < 7.45; i++)" — 7 iterations. An int
    // counter (with i derived from it) is used here for GLSL ES 1.00 (Android GLES2) portability.
    for (int k = 0; k < 7; k++) {
        float i = 1.3232 + float(k);
        p += t * 0.05;
        pom += t * 0.09;
        vec2 n = mixNoise(i * p * 0.3244243 + t * 0.131321);
        n *= rot(t * 0.5 - (0.03456 * p.x + 0.0342322 * p.y) * 50.0);
        p += n * 0.5;
        total += (sin(noise(p) * 8.5) * 0.55 + 0.4566) / amplitude;
        p = mix(pom, p, 0.5);
        amplitude *= 1.3;
        p *= 2.007556;
        pom *= 1.6895367;
    }
    return total;
}

void main() {
    float f = fbm(vCoord);
    vec3 col = vec3(0.212, 0.08, 0.03) / max(f, 0.0001);
    col = pow(col, vec3(1.5));

    // The ShaderToy colour above is authored in display (sRGB) space — that dark-crust / bright-vein
    // look is what should reach the screen. Reproduce it through Ialon's gamma pipeline :
#ifdef MANUAL_SRGB
    // Android GLES : no hardware sRGB framebuffer encode — emit the display-space colour directly.
    gl_FragColor = vec4(col, 1.0);
#else
    // Desktop : the hardware sRGB framebuffer will encode linear -> sRGB, so pre-convert sRGB -> linear
    // here to cancel it out and land back on the authored colour.
    gl_FragColor = vec4(pow(col, vec3(2.2)), 1.0);
#endif
}
