#version 450
#extension GL_GOOGLE_include_directive : require
#include "pc_block.glsl"

layout(location = 0) in vec2 v_TexCoord;
layout(location = 1) in vec4 v_Color;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler2D Sampler0;

void main() {
    vec4 texColor = texture(Sampler0, v_TexCoord);
    fragColor = texColor * v_Color * u_ColorModifier;
    if (fragColor.a < u_AlphaDiscard) discard;
}
