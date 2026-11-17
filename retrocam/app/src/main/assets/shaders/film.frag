#version 100
#extension GL_OES_EGL_image_external : require
precision highp float;

// Emulación de rollo en vivo. Mismo orden de pasos que FilmEngine.kt (versión aproximada).

uniform samplerExternalOES uTex;
uniform sampler2D uCurves;
uniform mat4 uTexMatrix;
uniform mat3 uToBuf;
uniform mat3 uToDisp;
uniform vec2 uDispRes;
uniform float uTime;
uniform float uGain;
uniform mat3 uMix;
uniform float uMono;
uniform vec3 uMonoW;
uniform float uSat;
uniform float uRollHi;
uniform float uRollLo;
uniform vec3 uShadowTint;
uniform vec3 uHighTint;
uniform float uSoft;
uniform float uCA;
uniform float uBloom;
uniform float uHal;
uniform float uGrain;
uniform float uGrainPx;
uniform float uGrainColor;
uniform float uVignette;
uniform float uFlash;
uniform vec4 uLeak0;
uniform vec3 uLeakC0;
uniform vec4 uLeak1;
uniform vec3 uLeakC1;
uniform float uIntensity;

varying vec2 vUv;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

vec3 samp(vec2 d) {
    vec2 b = (uToBuf * vec3(clamp(d, 0.0, 1.0), 1.0)).xy;
    return texture2D(uTex, (uTexMatrix * vec4(b, 0.0, 1.0)).xy).rgb;
}

float hash(vec2 p) {
    p = mod(p, 2048.0);
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec3 applyCurves(vec3 c) {
    c = clamp(c, 0.0, 1.0) * (255.0 / 256.0) + 0.5 / 256.0;
    return vec3(
        texture2D(uCurves, vec2(c.r, 0.5)).r,
        texture2D(uCurves, vec2(c.g, 0.5)).g,
        texture2D(uCurves, vec2(c.b, 0.5)).b);
}

vec3 leak(vec3 c, vec2 pp, float longSide, vec4 L, vec3 col) {
    if (L.w <= 0.0) return c;
    vec2 dd = (pp - vec2(L.x, 1.0 - L.y) * uDispRes) / longSide;
    float q = dot(dd, dd) / (L.z * L.z);
    float f = L.w * exp(-2.0 * q);
    return 1.0 - (1.0 - clamp(c, 0.0, 1.0)) * (1.0 - col * f);
}

void main() {
    vec2 d = (uToDisp * vec3(vUv, 1.0)).xy;
    vec2 px = 1.0 / uDispRes;
    vec3 center = samp(d);
    vec3 c = center;

    // Suavidad
    if (uSoft > 0.0) {
        vec2 s = px * uSoft;
        c = center * 0.4 + (samp(d + vec2(s.x, 0.0)) + samp(d - vec2(s.x, 0.0)) +
                            samp(d + vec2(0.0, s.y)) + samp(d - vec2(0.0, s.y))) * 0.15;
    }
    // Aberración cromática
    if (uCA > 0.0) {
        vec2 off = d - 0.5;
        c.r = samp(0.5 + off * (1.0 + uCA)).r;
        c.b = samp(0.5 + off * (1.0 - uCA)).b;
    }

    c *= uGain;
    c = uMix * c;
    float m = dot(c, uMonoW);
    c = mix(c, vec3(m), uMono);
    c = applyCurves(c);

    float l = dot(c, LUMA);
    float s = uSat * (1.0 - uRollHi * smoothstep(0.55, 1.0, l) - uRollLo * (1.0 - smoothstep(0.0, 0.35, l)));
    c = vec3(l) + (c - vec3(l)) * s;
    c += uShadowTint * (1.0 - l) * (1.0 - l) + uHighTint * l * l;

    vec2 pp = d * uDispRes;
    float longSide = max(uDispRes.x, uDispRes.y);

    // Bloom y halación (aproximados con un anillo de muestras)
    if (uBloom + uHal > 0.0) {
        float gb = 0.0;
        float gh = 0.0;
        float rad = 0.02 * longSide;
        for (int i = 0; i < 6; i++) {
            float a = float(i) * 1.0472 + 0.5;
            vec2 o = vec2(cos(a), sin(a)) * rad * px;
            float ls = dot(samp(d + o), LUMA);
            float vb = smoothstep(0.55, 1.0, ls);
            float vh = smoothstep(0.72, 1.0, ls);
            gb += vb * vb;
            gh += vh * vh;
        }
        gb /= 6.0;
        gh = min(1.0, gh / 6.0 * 1.2);
        float bl = gb * uBloom;
        c = 1.0 - (1.0 - clamp(c, 0.0, 1.0)) * (1.0 - bl * vec3(1.0, 0.96, 0.9));
        c += gh * uHal * vec3(1.0, 0.25, 0.05);
    }

    float dist = length(pp - 0.5 * uDispRes) / (0.5 * length(uDispRes));
    if (uFlash > 0.0) c *= 1.0 + uFlash * 0.35 * (1.0 - dist * dist);

    c = leak(c, pp, longSide, uLeak0, uLeakC0);
    c = leak(c, pp, longSide, uLeak1, uLeakC1);

    float vig = uVignette + uFlash * 0.25;
    c *= 1.0 - vig * smoothstep(0.3, 1.05, dist);

    // Grano animado a 24 cuadros por segundo
    if (uGrain > 0.0) {
        float lg = clamp(dot(c, LUMA), 0.0, 1.0);
        float w = (0.35 + 2.6 * lg * (1.0 - lg)) * uGrain;
        float t = floor(uTime * 24.0);
        vec2 gp = pp / uGrainPx + vec2(t * 17.0, t * 31.0);
        float n = (vnoise(gp) * 0.55 + vnoise(gp / 2.3 + 50.0) * 0.25 + hash(pp + t) * 0.2 - 0.5) * 2.4;
        float nr = (hash(pp * 1.3 + t + 11.0) - 0.5) * 1.6 * uGrainColor;
        float nb = (hash(pp * 0.7 + t + 23.0) - 0.5) * 1.6 * uGrainColor;
        c += vec3(n + nr, n, n + nb) * w;
    }

    c = mix(center, c, uIntensity);
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
