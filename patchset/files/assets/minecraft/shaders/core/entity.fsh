#version 330 core

in vec4 v_Color;
in vec2 v_TexCoord;
in float v_FogDist;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;        // lightmap (block+sky light gradient)
uniform vec2 u_LightmapCoord;      // this entity's lightmap coord, raw 0..240 (matches block UV1)
uniform vec4 u_ColorModifier;
uniform vec4 u_OverlayColor;   // hurt-flash / colour-multiplier: rgb tint, a = mix factor
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
    vec4 color = texColor * v_Color * u_ColorModifier;
    if (color.a < u_AlphaDiscard) discard;
    // Environmental block+sky light: modulate by the lightmap at this entity's coord
    // (same /256 convention as block.vsh) so entities darken in shade like the world.
    color.rgb *= texture(Sampler1, u_LightmapCoord / 256.0).rgb;
    // Hurt flash / colour multiplier: blend toward the overlay tint (no-op when a == 0).
    color.rgb = mix(color.rgb, u_OverlayColor.rgb, u_OverlayColor.a);
    if (u_FogEnabled != 0) {
        float f = clamp(computeFog(v_FogDist), 0.0, 1.0);
        color.rgb = mix(u_FogColor.rgb, color.rgb, f);
    }
    fragColor = color;
}
