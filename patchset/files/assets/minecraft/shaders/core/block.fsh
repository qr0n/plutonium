#version 330 core

in vec4 v_Color;
in vec2 v_TexCoord;
in vec2 v_LightCoord;
in float v_FogDist;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec4 u_ColorModifier;
uniform float u_AlphaDiscard;

uniform int u_FogEnabled;
uniform int u_FogMode;
uniform float u_FogStart;
uniform float u_FogEnd;
uniform float u_FogDensity;
uniform vec4 u_FogColor;

out vec4 fragColor;

float computeFog(float dist) {
    if (u_FogMode == 2048) {
        return exp(-u_FogDensity * dist);
    } else if (u_FogMode == 2049) {
        return exp(-pow(u_FogDensity * dist, 2.0));
    } else {
        return clamp((u_FogEnd - dist) / (u_FogEnd - u_FogStart), 0.0, 1.0);
    }
}

void main() {
    vec4 texColor = texture(Sampler0, v_TexCoord);
    vec4 lightColor = texture(Sampler1, v_LightCoord);
    vec4 color = texColor * v_Color * lightColor * u_ColorModifier;
    if (color.a < u_AlphaDiscard) discard;

    if (u_FogEnabled != 0) {
        float f = clamp(computeFog(v_FogDist), 0.0, 1.0);
        color.rgb = mix(u_FogColor.rgb, color.rgb, f);
    }
    fragColor = color;
}
