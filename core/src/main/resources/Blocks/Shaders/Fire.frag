#import "Common/ShaderLib/GLSLCompat.glsllib"

// Procedural fire-flame fragment shader.
// Adapted from the classic ShaderToy "Fire Flame" (procedural simplex-ish value noise from IQ +
// fractal sum, shaped into an upward-tapering plume). The original draws an opaque flame over a
// black background ; here the background is made transparent (alpha = flame luminance) so the flame
// sits on the two crossing planes of the fire block and the empty corners disappear.

uniform float g_Time;
uniform float m_Speed;

in vec2 vUv;      // raw CROSS_PLANE UV : padded into 0.25..0.75 by the shape (see Shape.UV_PADDING)
in vec3 vWorldPos; // world position, used to derive a stable per-block animation phase

// procedural noise from IQ
vec2 hash(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)),
             dot(p, vec2(269.5, 183.3)));
    return -1.0 + 2.0 * fract(sin(p) * 43758.5453123);
}

float noise(in vec2 p) {
    const float K1 = 0.366025404; // (sqrt(3)-1)/2;
    const float K2 = 0.211324865; // (3-sqrt(3))/6;

    vec2 i = floor(p + (p.x + p.y) * K1);

    vec2 a = p - i + (i.x + i.y) * K2;
    vec2 o = (a.x > a.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    vec2 b = a - o + K2;
    vec2 c = a - 1.0 + 2.0 * K2;

    vec3 h = max(0.5 - vec3(dot(a, a), dot(b, b), dot(c, c)), 0.0);

    vec3 n = h * h * h * h * vec3(dot(a, hash(i + 0.0)), dot(b, hash(i + o)), dot(c, hash(i + 1.0)));

    return dot(n, vec3(70.0));
}

float fbm(vec2 uv) {
    float f;
    mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    f  = 0.5000 * noise(uv); uv = m * uv;
    f += 0.2500 * noise(uv); uv = m * uv;
    f += 0.1250 * noise(uv); uv = m * uv;
    f = 0.5 + 0.5 * f;
    return f;
}

void main() {
    // Remap the padded shape UV (0.25..0.75) back to a full 0..1 tile for the flame.
    vec2 uv = clamp((vUv - 0.25) * 2.0, 0.0, 1.0);

    // Per-block animation phase : floor(worldPos + 0.5) is the integer cell the block occupies
    // (its planes span +/-0.5 around the cell centre), so this is constant across the whole quad
    // — no within-flame seam — yet differs from one fire to the next. A hash spreads the phases.
    vec3 cell = floor(vWorldPos + 0.5);
    float phase = fract(sin(dot(cell.xz, vec2(12.9898, 78.233)) + cell.y * 37.719) * 43758.5453) * 100.0;
    float iTime = g_Time * m_Speed + phase;

    vec2 q = uv;
    q.y *= 2.0;
    float strength = floor(q.x + 1.0) + 2.0;
    float T3 = max(3.0, 1.25 * strength) * iTime * 0.5;
    q.x = mod(q.x, 1.0) - 0.5;

    // Set the flame width (lower = broader, higher = thinner)
    q.x *= 0.8;

    q.y -= 0.25;
    float n = fbm(strength * q - vec2(0.0, T3));
    float c = 1.0 - 16.0 * pow(max(0.0, length(q * vec2(1.8 + q.y * 1.5, 0.75)) - n * max(0.0, q.y + 0.25)), 1.2);
    // Clamp the plume term to >= 0 BEFORE it feeds c1 : outside the plume c goes negative, and so
    // does the vertical fall-off near the top of the tile ; their product would flip positive and
    // saturate c1 (and thus the colour) to opaque white — the spurious "white cross" at the top.
    float c1 = n * max(c, 0.0) * (1.5 - pow(1.25 * uv.y, 4.0));
    c1 = clamp(c1, 0.0, 1.0);

    vec3 col = vec3(1.5 * c1, 1.5 * c1 * c1 * c1, c1 * c1 * c1 * c1 * c1 * c1);

    // Transparency : the flame's own luminance. Black (empty) corners become fully transparent.
    float alpha = clamp(max(col.r, max(col.g, col.b)), 0.0, 1.0);
    if (alpha < 0.02) {
        discard;
    }

#ifdef MANUAL_SRGB
    // Emulate the sRGB framebuffer encode where the hardware one is missing (Android GLES).
    col = pow(col, vec3(1.0 / 2.2));
#endif

    gl_FragColor = vec4(col, alpha);
}
