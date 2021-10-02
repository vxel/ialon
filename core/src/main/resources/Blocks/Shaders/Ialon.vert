#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Instancing.glsllib"
#import "Common/ShaderLib/Skinning.glsllib"
#import "Common/ShaderLib/Lighting.glsllib"
#import "Common/ShaderLib/MorphAnim.glsllib"

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
varying vec2 texCoord;

varying vec3 AmbientSum;
varying vec4 DiffuseSum;
varying vec3 SpecularSum;

attribute vec3 inPosition;
attribute vec2 inTexCoord;
attribute vec3 inNormal;

const float tileSize = 64;
const float atlasSize = 2048;
const float rw = tileSize / atlasSize;

#ifdef VERTEX_COLOR
    attribute vec4 inColor;
    flat varying int SunIntensity;
    const float levels[16] = float[16](
        0.0,
        1.0 / 255.0,
        2.0 * 2.0 / 255.0,
        3.0 * 3.0 / 255.0,
        4.0 * 4.0 / 255.0,
        5.0 * 5.0 / 255.0,
        6.0 * 6.0 / 255.0,
        7.0 * 7.0 / 255.0,
        8.0 * 8.0 / 255.0,
        9.0 * 9.0 / 255.0,
        10.0 * 10.0 / 255.0,
        11.0 * 11.0 / 255.0,
        12.0 * 12.0 / 255.0,
        13.0 * 13.0 / 255.0,
        14.0 * 14.0 / 255.0,
        1.0
    );
#endif

void main() {
   vec4 modelSpacePos = vec4(inPosition, 1.0);
   vec3 modelSpaceNorm = inNormal;

   gl_Position = TransformWorldViewProjection(modelSpacePos);
   texCoord = inTexCoord;
   //texCoord.x = (inTexCoord.x + 0.5f * rw);
   //texCoord.y = (inTexCoord.y + 0.5f * rw);

   vec3 wvPosition = TransformWorldView(modelSpacePos).xyz;
   vec3 wvNormal  = normalize(TransformNormal(modelSpaceNorm));
   vec3 viewDir = normalize(-wvPosition);

    #ifdef MATERIAL_COLORS
        AmbientSum  = m_Ambient.rgb * g_AmbientLightColor.rgb;
        SpecularSum = m_Specular.rgb;
        DiffuseSum = m_Diffuse;
    #else
        // Defaults: Ambient and diffuse are white, specular is black.
        AmbientSum  = g_AmbientLightColor.rgb;
        SpecularSum = vec3(0.0);
        DiffuseSum = vec4(1.0);
    #endif

    #ifdef VERTEX_COLOR
        SunIntensity = (int(inColor.r) >> 4) & 0xF;
        int TorchIntensity = (int(inColor.r)) & 0xF;
        float lightLevel = min(levels[SunIntensity], AmbientSum.r);
        lightLevel = max(lightLevel, levels[TorchIntensity]);
        AmbientSum = vec3(lightLevel, lightLevel, lightLevel);
        DiffuseSum *= vec4(AmbientSum.r, AmbientSum.g, AmbientSum.b, inColor.a);
    #endif

    #ifdef VERTEX_LIGHTING
        int i = 0;
        vec3 diffuseAccum  = vec3(0.0);
        vec3 specularAccum = vec3(0.0);
        vec4 diffuseColor;
        vec3 specularColor;
        vec4 lightColor = g_LightData[1];
        vec4 lightData1 = g_LightData[2];
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
        // allow use of control flow
        if(lightColor.w > 1.0){
        #endif
            vec4 lightDirection = g_LightData[3];
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