#version 120
varying vec3 position;
varying vec4 uv;
varying vec4 lcolor;
varying float intens;

struct Light {
	vec4 color;
	vec3 position;
	vec3 heading;
	float angle;
};

// All positions are relative to the camera (LightManager.cameraPos), so they stay precise far from the world origin.
// Offset of the chunk being drawn (gl_Vertex is chunk-local); 0 for particles, whose vertices are already camera-relative.
uniform vec3 chunkOffset;
uniform sampler2D sampler;
uniform sampler2D lightmap;
uniform mat4 modelview;
uniform Light lights[100];
uniform int lightCount;
uniform int maxLights;
uniform float ticks;

float distSq(vec3 a, vec3 b) {
	vec3 d = a - b;
	return dot(d, d);
}

vec3 snormalize(vec3 v) {
	float len = length(v);
	return (len == 0.0) ? vec3(0.0, 1.0, 0.0) : v / len;
}

//Returns the *angle* between two vectors of any length
float angle(vec3 a, vec3 b) {
	return acos(clamp(dot(snormalize(a), snormalize(b)), -1.0, 1.0));
}

//Intensity of one light at a position: cone attenuation combined with distance attenuation
float lightIntensity(int i, vec3 pos) {
	float radius = length(lights[i].heading);
	if (radius <= 0.0 || distSq(lights[i].position, pos) > radius * radius) {
		return 0.0;
	}

	//Lights with an angle of at least PI shine in all directions
	float coneIntensity = 1.0;
	if (lights[i].angle < 3.14159) {
		float angleFromHeading = angle(pos - lights[i].position, lights[i].heading);
		float angleDelta = max(lights[i].angle - angleFromHeading, 0.0);
		angleDelta /= (lights[i].angle == 0.0) ? 0.001 : lights[i].angle;
		coneIntensity = min(angleDelta * angleDelta, 1.0);
	}

	float distIntensity = pow(max(0.0, 1.0 - distance(lights[i].position, pos) / radius), 2.0);
	return min(coneIntensity, distIntensity);
}

void main() {
	position = gl_Vertex.xyz + chunkOffset;

	gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
	gl_TexCoord[1] = gl_TextureMatrix[1] * gl_MultiTexCoord1;

	gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;

	gl_FrontColor = gl_Color;

	float sumR = 0.0;
	float sumG = 0.0;
	float sumB = 0.0;
	float maxIntens = 0.0;
	float totalIntens = 0.0;

	//Find total intensity
	for (int i = 0; i < lightCount; i ++) {
		float intensity = lightIntensity(i, position);
		totalIntens += intensity;
		maxIntens = max(maxIntens, intensity);
	}

	//find the color, whose brightness gets scaled by the total light intensity
	if (totalIntens > 0.0) {
		for (int i = 0; i < lightCount; i ++) {
			float intensity = lightIntensity(i, position) * lights[i].color.w;
			sumR += (intensity/totalIntens)*lights[i].color.x;
			sumG += (intensity/totalIntens)*lights[i].color.y;
			sumB += (intensity/totalIntens)*lights[i].color.z;
		}
	}

	//bake the color sums (clamped to zero) into a vec4
	lcolor = vec4(max(sumR*1.5, 0.0), max(sumG*1.5, 0.0), max(sumB*1.5, 0.0), 1.0);
	intens = min(1.0, maxIntens);
}
