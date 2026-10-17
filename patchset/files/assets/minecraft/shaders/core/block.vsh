#version 330 core

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;

uniform mat4 u_MVP;

out vec4 v_Color;
out vec2 v_TexCoord;
out vec2 v_LightCoord;
out float v_FogDist;

void main() {
    gl_Position = u_MVP * vec4(Position, 1.0);
    v_Color = Color;
    v_TexCoord = UV0;
    v_LightCoord = vec2(UV1) / 256.0;
    // Eye-space depth: for a perspective projection clip.w == -eye.z, i.e. the planar
    // view distance GL fixed-function fog used. (Ortho/GUI gives w==1, but fog is off there.)
    v_FogDist = gl_Position.w;
}
