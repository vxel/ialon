#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform vec4 m_Color;

void main() {
    vec3 c = m_Color.rgb;
#ifdef MANUAL_SRGB
    // No hardware sRGB framebuffer (Android GLES) : the colour is authored linear, encode it here.
    c = pow(c, vec3(1.0 / 2.2));
#endif
    gl_FragColor = vec4(c, m_Color.a);
}
