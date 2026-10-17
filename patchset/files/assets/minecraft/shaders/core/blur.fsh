#version 330 core

// Separable 9-tap gaussian; run once with u_Direction=(1,0) and once with (0,1).
in vec2 v_TexCoord;

uniform sampler2D Sampler0;
uniform vec2 u_TexelSize;
uniform vec2 u_Direction;

out vec4 fragColor;

void main() {
    float w[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
    vec2 off = u_TexelSize * u_Direction;
    vec3 c = texture(Sampler0, v_TexCoord).rgb * w[0];
    for (int i = 1; i < 5; i++) {
        c += texture(Sampler0, v_TexCoord + off * float(i)).rgb * w[i];
        c += texture(Sampler0, v_TexCoord - off * float(i)).rgb * w[i];
    }
    fragColor = vec4(c, 1.0);
}
