#version 330 core

in vec4 v_Color;

uniform vec4 u_ColorModifier;
uniform float u_AlphaDiscard;

out vec4 fragColor;

void main() {
    fragColor = v_Color * u_ColorModifier;
    if (fragColor.a < u_AlphaDiscard) discard;
}
