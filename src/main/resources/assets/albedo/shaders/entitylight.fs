#version 120
varying vec3 position;
varying float intens;
varying vec4 lcolor;
varying vec4 uv;

uniform sampler2D sampler;
uniform sampler2D lightmap;
uniform vec3 playerPos;
// Active GL fog mode: 0 = fog off, 9729 = GL_LINEAR, 2048 = GL_EXP, 2049 = GL_EXP2
uniform int fogMode;
// 1 = fade coloured light out where vanilla block light is 0 (walls block it)
uniform int blockLightOcclusion;

float fogFactor(float dist) {
	if (fogMode == 2048) {
		return clamp(exp(-gl_Fog.density * dist), 0.0, 1.0);
	} else if (fogMode == 2049) {
		float d = gl_Fog.density * dist;
		return clamp(exp(-d * d), 0.0, 1.0);
	} else if (fogMode == 9729) {
		return clamp((gl_Fog.end - dist) * gl_Fog.scale, 0.0, 1.0);
	}
	return 1.0;
}

void main() {
	vec3 lightdark = texture2D(lightmap,gl_TexCoord[1].st).xyz;
	lightdark = clamp(lightdark, 0.0, 1.0);

	vec3 lcolor_2 = clamp(lcolor.xyz*intens, 0.0, 1.0);

	if (blockLightOcclusion == 1) {
		//lightmap u = (block light * 16 + 8) / 256; full colour from block light 3 upwards
		float blockLight = clamp((gl_TexCoord[1].s * 256.0 - 8.0) / 240.0, 0.0, 1.0);
		lcolor_2 *= clamp(blockLight * 5.0, 0.0, 1.0);
	}

	//lightdark = lightdark + lcolor_2;   //More washed-out, but more physically correct
	lightdark = max(lightdark, lcolor_2); //Vivid but unrealistic

	//combine texture with lighting
	vec4 baseColor = gl_Color * texture2D(sampler,gl_TexCoord[0].st);
	baseColor = baseColor * vec4(lightdark, 1);

	//debug
	//baseColor = vec4(lcolor_2.xyz,1);

	//Fog: same formula as the fixed-function pipeline (eye-space depth). The old formula ignored gl_Fog.end and
	//turned entities fully fog-coloured a few blocks past gl_Fog.start, which is very close in the Nether.
	float fog = fogFactor(gl_FragCoord.z / gl_FragCoord.w);
	baseColor = vec4(mix(vec3(gl_Fog.color), baseColor.xyz, fog), baseColor.w);

	gl_FragColor = baseColor;
}
