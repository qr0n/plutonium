#version 450
#extension GL_GOOGLE_include_directive : require
#include "pc_block.glsl"

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_LightCoord;
layout(location = 3) in float v_FogDist;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler2D Sampler0;
layout(set = 0, binding = 1) uniform sampler2D Sampler1;

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
