uniform mat4 g_WorldViewProjectionMatrix;
uniform float m_Scale;
uniform float m_Seed;
uniform float m_WaterLevel;

attribute vec3 inPosition;
attribute vec2 inTexCoord;

varying vec2 uv;

void main() {
    uv = inTexCoord;
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
}
