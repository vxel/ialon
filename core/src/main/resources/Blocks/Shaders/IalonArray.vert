#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Instancing.glsllib"
#import "Common/ShaderLib/Lighting.glsllib"

#ifdef VERTEX_LIGHTING
    #import "Common/ShaderLib/BlinnPhongLighting.glsllib"
#endif

uniform vec4 m_Ambient;
uniform vec4 m_Diffuse;
uniform vec4 m_Specular;
uniform float m_Shininess;

#if defined(VERTEX_LIGHTING)
    uniform vec4 g_LightData[NB_LIGHTS];
#endif
uniform vec4 g_AmbientLightColor;
uniform vec3 g_CameraPosition;

attribute vec3 inPosition;
attribute vec2 inTexCoord;
attribute vec3 inNormal;
// Texture-array layer index for this vertex (bound as the 1-component TexCoord2 buffer).
attribute float inTexCoord2;

// Animated texture (flowing water)
uniform float g_Time;
uniform float m_TextureScrollSpeedX;
uniform float m_TextureScrollSpeedY;

out vec3 AmbientSum;
out vec4 DiffuseSum;
out vec3 SpecularSum;

out vec2 texCoord;
flat out float layer;

#ifdef VERTEX_COLOR
    attribute vec4 inColor;
    const float lightDecay = 2.2;
    const float levels[16] = float[16](
        0.0,
        pow(1.0 / 15.0, lightDecay),
        pow(2.0 / 15.0, lightDecay),
        pow(3.0 / 15.0, lightDecay),
        pow(4.0 / 15.0, lightDecay),
        pow(5.0 / 15.0, lightDecay),
        pow(6.0 / 15.0, lightDecay),
        pow(7.0 / 15.0, lightDecay),
        pow(8.0 / 15.0, lightDecay),
        pow(9.0 / 15.0, lightDecay),
        pow(10.0 / 15.0, lightDecay),
        pow(11.0 / 15.0, lightDecay),
        pow(12.0 / 15.0, lightDecay),
        pow(13.0 / 15.0, lightDecay),
        pow(14.0 / 15.0, lightDecay),
        1.0
    );
#endif

void main() {
    vec4 modelSpacePos = vec4(inPosition, 1.0);
    vec3 modelSpaceNorm = inNormal;

    gl_Position = TransformWorldViewProjection(modelSpacePos);

    layer = inTexCoord2;

    // Local [0,1] UVs over the tile. Flowing water scrolls by g_Time ; the sampler's Repeat wrap tiles
    // the (seamlessly mirror-bordered) layer natively -- no per-tile atlas wrap math needed.
    if (abs(m_TextureScrollSpeedX) > 0.0 || abs(m_TextureScrollSpeedY) > 0.0) {
        vec2 delta = vec2(mod(g_Time * m_TextureScrollSpeedX, 1.0), mod(g_Time * m_TextureScrollSpeedY, 1.0));
        texCoord = inTexCoord - delta;
    } else {
        texCoord = inTexCoord;
    }

    vec3 wvPosition = TransformWorldView(modelSpacePos).xyz;
    vec3 wvNormal  = normalize(TransformNormal(modelSpaceNorm));
    vec3 viewDir = normalize(-wvPosition);

    #ifdef MATERIAL_COLORS
         AmbientSum  = m_Ambient.rgb * g_AmbientLightColor.rgb;
         SpecularSum = m_Specular.rgb;
         DiffuseSum = m_Diffuse;
    #else
         AmbientSum  = g_AmbientLightColor.rgb;
         SpecularSum = vec3(0.0);
         DiffuseSum = vec4(1.0);
    #endif

    #ifdef VERTEX_COLOR
         // inColor is a normalized UnsignedByte buffer, so the alpha channel (the packed light level,
         // sunlight in the high nibble, torch in the low nibble) arrives in [0,1] : scale back to
         // [0,255] with rounding before unpacking the nibbles.
         int packedLight = int(inColor.a * 255.0 + 0.5);
         int SunIntensity = (packedLight >> 4) & 0xF;
         int TorchIntensity = packedLight & 0xF;

         float lum = AmbientSum.r * 0.3 + AmbientSum.g * 0.59 + AmbientSum.b * 0.11;
         float sunlight = levels[SunIntensity] * lum;
         float torchlight = levels[TorchIntensity];

         float lightLevel = min(max(sunlight, torchlight), 0.7f);

         AmbientSum.xyz = lightLevel * inColor.rgb;
         DiffuseSum *= vec4(AmbientSum.r, AmbientSum.g, AmbientSum.b, 1);
    #endif

    #ifdef VERTEX_LIGHTING
         vec3 diffuseAccum  = vec3(0.0);
         vec3 specularAccum = vec3(0.0);
         vec4 diffuseColor;
         vec3 specularColor;
         vec4 lightColor = g_LightData[0];
         vec4 lightData1 = g_LightData[1];
         #ifdef MATERIAL_COLORS
             diffuseColor  = m_Diffuse * vec4(lightColor.rgb, 1.0);
             specularColor = m_Specular.rgb * lightColor.rgb;
         #else
             diffuseColor  = vec4(lightColor.rgb, 1.0);
             specularColor = vec3(0.0);
         #endif

         vec4 lightDir;
         vec3 lightVec;
         lightComputeDir(wvPosition, lightColor.w, lightData1, lightDir, lightVec);

         float spotFallOff = 1.0;
         #if __VERSION__ >= 110
         if (lightColor.w > 1.0) {
         #endif
              vec4 lightDirection = g_LightData[2];
              spotFallOff = computeSpotFalloff(lightDirection, lightVec);
         #if __VERSION__ >= 110
         }
         #endif
         vec2 light = computeLighting(wvNormal, viewDir, lightDir.xyz, lightDir.w  * spotFallOff, m_Shininess);

         diffuseAccum  += light.x * diffuseColor.rgb;
         specularAccum += light.y * specularColor;

         DiffuseSum.rgb  *= diffuseAccum.rgb;
         SpecularSum.rgb *= specularAccum.rgb;
    #endif
}
