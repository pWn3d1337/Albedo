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
// Camera-relative position of the entity, tile entity or hand being drawn (gl_Vertex is model-local).
uniform vec3 entityPos;
uniform vec3 playerPos;
uniform sampler2D sampler;
uniform sampler2D lightmap;
uniform mat4 modelview;
uniform Light lights[100];
uniform int lightCount;
uniform int maxLights;
uniform float ticks;
// 1 while GL_LIGHTING is enabled: the shader replaces the fixed-function vertex lighting, so it has to apply it itself
uniform int lightingEnabled;

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

//Vertex colour of the fixed-function pipeline with GL_LIGHTING and GL_COLOR_MATERIAL (ambient and diffuse), as set up by
//RenderHelper.enableStandardItemLighting: the directional shading of items, mobs and block entities. Lights are
//directional (w = 0) and their positions are already in eye space.
vec4 fixedFunctionLighting(vec4 color) {
	vec3 normal = normalize(gl_NormalMatrix * gl_Normal);
	vec3 lit = gl_LightModel.ambient.rgb * color.rgb;
	for (int i = 0; i < 2; i ++) {
		float diffuse = max(dot(normal, normalize(gl_LightSource[i].position.xyz)), 0.0);
		lit += (gl_LightSource[i].ambient.rgb + gl_LightSource[i].diffuse.rgb * diffuse) * color.rgb;
	}
	return vec4(clamp(lit, 0.0, 1.0), color.a);
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
	position = gl_Vertex.xyz + entityPos;

	gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
	gl_TexCoord[1] = gl_TextureMatrix[1] * gl_MultiTexCoord1;

	gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;

	gl_FrontColor = (lightingEnabled == 1) ? fixedFunctionLighting(gl_Color) : gl_Color;

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
