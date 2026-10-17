#version 330 core

layout(location = 0) in vec3 Position;
layout(location = 2) in vec2 UV0;

uniform mat4 u_MVP;

out vec2 v_TexCoord;

void main() {
    gl_Position = u_MVP * vec4(Position, 1.0);
    v_TexCoord = UV0;
}
