#import "Common/ShaderLib/GLSLCompat.glsllib"

// Steam escaping from a leak in the bottom pipe of a popup's frame, drawn straight onto the popup's
// backdrop quad (see Shaders/Steam.j3md and org.delaunois.ialon.ui.SteamBackground).
//
// Adapted from the classic ShaderToy "smoke" recipe : a fractal sum of noise, folded into eddies by
// re-sampling it at coordinates the previous pass displaced (domain warping). The reference fills the
// whole screen with a fog that thins out towards the top ; here the same field is confined to a PLUME —
// a cone rising from one point, narrow and dense at the hole, wide and faint at the top — so it reads as
// gas escaping from somewhere rather than as a screen-wide haze.
//
// The plume envelope is computed BEFORE the noise, and the shader leaves early wherever it is empty. That
// is not a micro-optimisation : the fractal sum is by far the most expensive thing here, the plume covers
// well under a third of the screen, and this backdrop is full-screen on a mid-range phone.
//
// The noise is the same IQ simplex-ish gradient noise the fire block uses (Blocks/Shaders/Fire.frag),
// kept local to each shader rather than shared : it is a 25-line classic, and Fire's version is tuned and
// device-tested as it stands.

uniform float g_Time;

uniform vec2 m_Resolution;
uniform vec2 m_Origin;
uniform vec4 m_BackColor;
uniform vec4 m_SteamColor;
uniform float m_Speed;
uniform float m_Density;
uniform float m_Scale;
uniform float m_Reach;
uniform float m_Spread;
uniform float m_Width;

varying vec2 vUv;

// How many times the field is folded back into itself. Each pass is one more fractal sum — three octaves
// of noise — so this is what the effect costs. Two passes already curl ; the third mostly adds the fine
// filaments that a phone screen cannot show anyway, so GLES (i.e. Android) stops at two.
#ifdef GL_ES
    #define WARP_PASSES 2
#else
    #define WARP_PASSES 3
#endif

// Upward drift of the column, in screen heights per second (at Speed = 1).
#define RISE 0.20
// How much the puffs are stretched by the time they reach the top of their reach : what turns the tight
// curls at the hole into slow, broad clouds higher up.
#define GROWTH 1.2
// Strength of the domain warp, in noise units. Too little and the plume is a smooth fog ; too much and it
// breaks into unrelated blobs.
#define WARP 0.45
// The power curve that turns the noise field into an opacity, at the hole and at the top of the reach.
// Below 1 the steam is thick and only lightly textured, above it only the peaks of the field survive.
#define MIN_EXPONENT 1.6
#define MAX_EXPONENT 4.0
// How the column widens with height : 1 is a straight-sided cone, below 1 blooms fast then settles.
#define SPREAD_CURVE 0.75
// Steepness of the bell across the cone (larger = tighter column, hugging its axis).
#define CONE_FALLOFF 2.5
// How far the noise is allowed to push the plume's own silhouette sideways, in half-widths.
#define EDGE_WANDER 0.55
// The jet at the leak itself : how tight it is across (in plume space, so it does NOT open with the
// cone), how fast it dies out with height, and how much it adds to the plume's own opacity.
#define JET_TIGHTNESS 900.0
#define JET_DECAY 55.0
#define JET_STRENGTH 0.45
// The column does not rise straight : it leans a little, and the lean travels upwards. Amplitude is in
// screen heights per screen height of climb, so the base stays put and only the top wanders.
#define SWAY_AMPLITUDE 0.05
#define SWAY_FREQUENCY 3.0
#define SWAY_SPEED 0.9
// Period of the noise lattice, in cells : how far the field can be sampled before it repeats. Its real
// job is to keep the hash's input bounded however long the game has been running (see hash()) ; the
// repetition is a side effect, and at this scale it is minutes away and seamless when it arrives.
#define LATTICE 512.0
#define TWO_PI 6.2831853

/**
 * Gradient for one lattice cell.
 *
 * <p>Both details here are precautions against ONE thing : this field scrolls with the clock, so the
 * coordinate it is sampled at keeps growing for as long as the game runs — tens of thousands of cells
 * after a few hours — and a hash only has to stay well-behaved that far out.
 *
 * <p>The cell is wrapped first, which bounds what the hash is ever asked to digest whatever the clock
 * says. It costs nothing in appearance : the wrap is applied to the INTEGER cell, so a corner shared by
 * two cells still hashes to one gradient and the field stays continuous — it merely becomes periodic, with
 * a period of LATTICE cells (many minutes of drift at this scale, and seamless when it comes round).
 *
 * <p>And no sine, unlike the fire block's copy of this noise (Blocks/Shaders/Fire.frag). The classic
 * {@code fract(sin(x) * 43758.5)} multiplies sin's rounding error by 43758, and mobile drivers are far
 * weaker than desktop ones at reducing a large angle. Measured on desktop, both versions hold up to a
 * simulated 20 000 s with no visible difference — so this is insurance for the target platform, which
 * cannot be tested here, and not a fix for anything observed. It is the fract/dot hash the star field
 * already uses (Shaders/Star.frag), and it drops a transcendental into the bargain.
 */
vec2 hash(vec2 cell) {
    vec3 p = fract(mod(cell, LATTICE).xyx * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return -1.0 + 2.0 * fract((p.xx + p.yz) * p.zy);
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

/** Three octaves, remapped to 0..1 (mean 0.5). */
float fbm(vec2 uv) {
    float f;
    mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    f  = 0.5000 * noise(uv); uv = m * uv;
    f += 0.2500 * noise(uv); uv = m * uv;
    f += 0.1250 * noise(uv);
    return 0.5 + 0.5 * f;
}

/**
 * Final colour. The parameters are LINEAR (the Java side builds them with setAsSrgb), which the hardware
 * sRGB framebuffer encodes on store — except where there is none (Android GLES), hence MANUAL_SRGB. Same
 * convention as Sky / Star / FarTerrain ; see the sRGB-pipeline note.
 */
vec4 shade(vec3 rgb, float a) {
#ifdef MANUAL_SRGB
    rgb = pow(rgb, vec3(1.0 / 2.2));
#endif
    return vec4(rgb, a);
}

void main() {

    // Plume space : the origin is the hole in the pipe, one unit is one screen height, y points up. Both
    // axes are divided by the same number, so the plume is never stretched by the aspect ratio, and its
    // shape holds from a phone in portrait to a wide desktop window.
    vec2 p = (vUv * m_Resolution - m_Origin) / m_Resolution.y;
    float h = p.y;
    float t = g_Time * m_Speed;

    // ---- the envelope : the bound of where the plume can be at all ----
    // Cheap, closed-form, and computed before anything else, because its only job is to answer "is there
    // any steam here ?" for the four fifths of the screen where the answer is no.
    // Only the top of the column wanders (the amplitude grows with the height climbed) : a leak does not
    // move, so the jet must leave the hole in the same place every frame.
    // The phase is wrapped into one turn, which is exact for a sine and keeps its argument small : left to
    // grow with the clock it would lose all its resolution, and the lean would go from smooth to jerky
    // after a few hours of play (the same precision trap as the noise hash, one floor lower).
    float sway = SWAY_AMPLITUDE * max(h, 0.0) * sin(h * SWAY_FREQUENCY - mod(t * SWAY_SPEED, TWO_PI));
    // The cone opens fast just above the leak, then slower : jet blooms as soon as it loses its pressure,
    // and after that it only drifts wider. A straight (linear) widening reads as a funnel — a cardboard
    // cone with steam painted in it — which is what the eye picks up first when the sides are that clean.
    float halfWidth = m_Width + m_Spread * pow(max(h, 0.0), SPREAD_CURVE);
    float x = (p.x - sway) / halfWidth;

    // A bell across the cone rather than a cut : any straight-edged falloff shows through as the outline
    // of a searchlight beam once the steam is dense, however soft the cut is made.
    float lateral = exp(-x * x * CONE_FALLOFF);
    float rise = smoothstep(-0.01, 0.05, h);               // nothing below the pipe
    float fade = 1.0 - smoothstep(m_Reach * 0.25, m_Reach, h); // dissipated well before the title
    float envelope = lateral * rise * fade;

    if (envelope <= 0.002) {
        // The bare veil — most of the screen — and the fractal sum below never runs for it.
        gl_FragColor = shade(m_BackColor.rgb, m_BackColor.a);
        return;
    }

    // ---- the steam ----
    // Sampled in plume space, divided by a factor that grows with height so the puffs swell as they climb,
    // and then scrolled downwards through the noise so the pattern travels UP the screen.
    //
    // The order of those two is not a detail, and getting it wrong is a slow-acting bug rather than a
    // wrong-looking frame : fold the scroll into the coordinate BEFORE dividing by a factor that varies
    // per pixel, and the clock's offset is divided by it too — so the offset's own variation across the
    // screen, which is what sets the vertical frequency, grows without bound as the game runs. The plume
    // looks right for the first minute, then progressively finer, and within a few minutes it has aliased
    // into horizontal streaks that read as the whole thing thickening. The scroll must stay a plain
    // translation in NOISE space, identical for every pixel, which is what this is.
    float grow = 1.0 + GROWTH * max(h, 0.0);
    vec2 s = vec2(p.x - sway, p.y) * (m_Scale / grow) - vec2(0.0, t * RISE * m_Scale);

    // Domain warp : re-sample the field at coordinates displaced by what the previous pass returned. This
    // is what curls the smooth fractal sum into eddies ; the small extra time offsets keep the successive
    // passes from locking into one rigid pattern.
    float n = fbm(s);
    n = fbm(s + WARP * vec2(n - 0.5, 0.6 * (n - 0.5)) - vec2(0.0, t * 0.15));
#if WARP_PASSES > 2
    n = fbm(s + WARP * vec2(0.7 * (n - 0.5), n - 0.5) - vec2(0.0, t * 0.25));
#endif

    // Turn the field into an opacity. A power curve, not a threshold : the exponent is what says how much
    // of the noise survives, and raising it with height is the whole trick of the reference shader — near
    // the hole a low exponent lifts almost everything (thick, milky steam, barely broken up), while higher
    // up a large one keeps only the peaks (thin filaments). It is a far softer transition than cutting the
    // field at a moving threshold, which reads as smoke rather than as vapour.
    float climb = clamp(h / m_Reach, 0.0, 1.0);
    float density = pow(n, MIN_EXPONENT + (MAX_EXPONENT - MIN_EXPONENT) * climb);

    // Re-shape with the cone's own bell DISPLACED by the field, rather than with the plain one used for
    // the early-out above : the silhouette then follows the same eddies that texture the inside, instead
    // of being a mathematically clean curve the noise merely fills. Free — n is already in hand — and it
    // is what stops the plume reading as a cone-shaped stencil. The early-out stays on the undisplaced
    // bell, whose tail is already below one part in 255 where it cuts.
    float displaced = x + EDGE_WANDER * (n - 0.5);
    density *= exp(-displaced * displaced * CONE_FALLOFF) * rise * fade * m_Density;

    // The jet : a small dense core at the leak itself, where the steam is still under pressure and has not
    // had the room to break up. It is what makes the plume read as coming OUT of a hole rather than as fog
    // that happens to sit at the bottom of the screen.
    //
    // Measured in plume space and NOT in the cone's own coordinate x : x is divided by a half-width that
    // grows with height, so a jet expressed in it would flare open with the cone instead of staying the
    // size of the hole.
    float jet = exp(-((p.x - sway) * (p.x - sway) * JET_TIGHTNESS + max(h, 0.0) * JET_DECAY)) * rise;
    density = clamp(density + JET_STRENGTH * jet, 0.0, 1.0);

    // Fresh steam is denser, and brighter, than the tail of the column.
    vec3 steam = m_SteamColor.rgb * (1.0 + 0.20 * exp(-max(h, 0.0) * 6.0));

    gl_FragColor = shade(mix(m_BackColor.rgb, steam, density),
                         mix(m_BackColor.a, m_SteamColor.a, density));
}
