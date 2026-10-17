#version 330 core

in vec2 v_TexCoord;
in vec4 v_Color;

uniform sampler2D Sampler0;
uniform vec4 u_ColorModifier;
uniform float u_AlphaDiscard;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(Sampler0, v_TexCoord);
    fragColor = texColor * v_Color * u_ColorModifier;
    if (fragColor.a < u_AlphaDiscard) discard;
}
