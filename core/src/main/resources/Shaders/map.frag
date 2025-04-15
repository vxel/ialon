#ifdef GL_ES
precision mediump float;
#endif

#define PI 3.14159265359
#define COL_WATER_LOW vec3(0.008, 0.169, 0.267)
#define COL_WATER_MID vec3(0.063, 0.318, 0.435)
#define COL_WATER_HIGH vec3(0.247, 0.412, 0.459)
#define COL_GROUND_LOW vec3(0.165, 0.4, 0.165)
#define COL_GROUND_MID vec3(0.58, 0.553, 0.353)
#define COL_GROUND_HIGH vec3(1.0, 1.0, 1.0)
//#define HIGH_P 1

uniform float m_Scale; // Larger means more ans smaller islands
uniform float m_Seed;
uniform float m_WaterLevel;

const bool bw = false;
const vec3 kSunDir = vec3(-0.624695, 0.468521, -0.624695);

varying vec2 uv;

vec3 linear_to_srgb(vec3 color) {
    const float p = 1. / 2.2;
    return vec3(pow(color.r, p), pow(color.g, p), pow(color.b, p));
}

vec3 srgb_to_linear(vec3 color) {
    const float p = 2.2;
    return vec3(pow(color.r, p), pow(color.g, p), pow(color.b, p));
}

vec3 grad(float l, vec3 cola, vec3 colb, vec3 colc, float a, float b, float h) {
    float m = h * (b - a) + a;
    return l < m
        ? mix(cola, colb, clamp(l - a, 0.0, 1.0) / (m - a))
        : mix(colb, colc, (l - m) / (b - m));
}

vec3 color(float level, float waterLevel, float dif) {
    return level < waterLevel
        ? grad(level, COL_WATER_LOW, COL_WATER_MID, COL_WATER_HIGH, 0.0, waterLevel, 0.5)
        : grad(level, COL_GROUND_LOW, COL_GROUND_MID, COL_GROUND_HIGH, waterLevel + 0.2, 1.0, 0.5) * dif;
}

#ifdef HIGH_P
// Produit un float pseudo-random dans [0..1] à partir d'un float quelconque
// @param f : un float
// @return : un float dans [0..1]
float hash11(float f) {
    return fract(sin(f) * 43758.5453123);
}

// Produit un float pseudo-random dans [0..1] à partir d'un vecteur de 2 float quelconques
// @param p : un vecteur de 2 float quelconques
// @return : un float dans [0..1]
float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}
#else
// low-quality
float hash11(float n) {
    return fract(n * 17.0 * fract(n * 0.3183099));
}


// low-quality
float hash21(vec2 p) {
    p  = 50.0 * fract(p * 0.3183099);
    return fract(p.x * p.y * (p.x + p.y));
}
#endif

// Fonction de bruit pour un point p quelconque.
// Les coordonnées entières autour du point p sont choisies de façon pseudo-aléatoires.
// Le résultat est une interpolation smooth (cubique) entre les 4 coordonnées entières
// autour du point p.
// @param p : la position du point (un vecteur de 2 float quelconques)
// @return : un float dans [0..1]
float noise(in vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f); // Interpolation cubique S(x) = 3x² - 2x³

    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));

    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

// value noise, and its analytical derivatives
vec3 noised(vec2 x) {
    vec2 i = floor(x);
    vec2 f = fract(x);
    vec2 u = f * f * (3.0 - 2.0 * f); // Interpolation cubique S(x) = 3x² - 2x³
    vec2 du = 6.0 * f * (1.0 - f);

    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));

    float k0 = a;
    float k1 = b - a;
    float k2 = c - a;
    float k4 = a - b - c + d;

    return vec3((k0 + k1 * u.x + k2 * u.y + k4 * u.x * u.y), // Value
                 du * vec2(k1 + k4 * u.y,                    // Derivative
                 k2 + k4 * u.x));
}

//==========================================================================================
// fbm constructions
//==========================================================================================

// Matrice de rotations pour mieux distribuer le bruit entre octaves fbm
const mat2 m2 = mat2(0.80, 0.60, -0.60, 0.80);

// Fractal Brownian Motion
// @param p : la position du point (un vecteur de 2 float quelconques)
// @return : un float dans [0..1]
float fbm(vec2 p) {
    float a;
    a  = 0.5000 * noise(p); p = p * m2 * 2.0;
    a += 0.2500 * noise(p); p = p * m2 * 2.0;
    a += 0.1250 * noise(p); p = p * m2 * 2.0;
    a += 0.0625 * noise(p); p = p * m2 * 2.0;
    return a;
}

// @return : ({float} noise value, {vec2} noise derivative
vec3 fbmd(vec2 p) {
    float s = 0.5;
    float a = 0.0;
    float b = 0.5;
    vec2 d = vec2(0.0);
    mat2 m = mat2(1.0, 0.0, 0.0, 1.0);
    for (int i = 0; i < 8; i++) {
        vec3 n = noised(p);
        a += b * n.x; // accumulate values
        d += b * n.yz; // accumulate derivative
        b *= s;
        p = p * m2 * 2.0;
    }

    return vec3(a, d);
}

float gaussian2D(vec2 uv) {
    vec2 mean = vec2(0.5);
    float sigma = 0.22;
    float flatten = 1.5; // < 1 = plus plat ; > 1 = plus pointu
    vec2 diff = uv - mean;
    float distSq = dot(diff, diff);
    float exponent = -pow(distSq / (2.0 * sigma * sigma), flatten);
    return exp(exponent);
}

void main() {
    vec2 pos = vec2(uv.x * m_Scale * 2., uv.y * m_Scale);

#ifdef GRAY_SCALE
    float h = fbm(pos + m_Seed);
    float ih = h * gaussian2D(uv);
    ih = pow(ih, 1.2) * 1.3;
    vec4 col = vec4(vec3(ih), 1.0);
    gl_FragColor = pow(col, vec4(2.2));
#else
    vec3 e = fbmd(pos + m_Seed);
    float h = e.x;

    // Terrain normal
    vec3 n = normalize(vec3(-e.y, 1.0, -e.z));
    float dif = 0.8 * clamp(dot(n, kSunDir), 0.0, 1.0) + 0.2;

    float ih = h * gaussian2D(uv);
    ih = pow(ih, 1.2) * 1.3;
    vec4 col = bw ? vec4(vec3(ih), 1.0) : vec4(color(ih, m_WaterLevel, dif), 1.0);

    gl_FragColor = pow(col, vec4(2.2));
#endif
}
