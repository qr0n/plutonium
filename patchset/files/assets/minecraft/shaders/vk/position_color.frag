#version 450
#extension GL_GOOGLE_include_directive : require
#include "pc_block.glsl"

layout(location = 0) in vec4 v_Color;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = v_Color * u_ColorModifier;
    if (fragColor.a < u_AlphaDiscard) discard;
}
