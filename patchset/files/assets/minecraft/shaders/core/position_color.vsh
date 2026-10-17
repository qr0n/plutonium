#version 330 core

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

uniform mat4 u_MVP;

out vec4 v_Color;

void main() {
    gl_Position = u_MVP * vec4(Position, 1.0);
    v_Color = Color;
}
