#version 100
#extension GL_OES_EGL_image_external : require
precision highp float;

// Cinta VHS: baja resolución, color corrido, inestabilidad horizontal,
// banda de tracking, ruido de cambio de cabezas, nieve y líneas de barrido.

uniform samplerExternalOES uTex;
uniform sampler2D uOsd;
uniform mat4 uTexMatrix;
uniform mat3 uToBuf;
uniform mat3 uToDisp;
uniform vec2 uDispRes;
uniform float uTime;
uniform float uDamage;
uniform float uOsdOn;

varying vec2 vUv;

float hash(vec2 p) {
    p = mod(p, 2048.0);
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 samp(vec2 d) {
    vec2 b = (uToBuf * vec3(clamp(d, 0.0, 1.0), 1.0)).xy;
    return texture2D(uTex, (uTexMatrix * vec4(b, 0.0, 1.0)).xy).rgb;
}

vec3 rgb2yiq(vec3 c) {
    return vec3(dot(c, vec3(0.299, 0.587, 0.114)),
                dot(c, vec3(0.596, -0.274, -0.322)),
                dot(c, vec3(0.211, -0.523, 0.312)));
}

vec3 yiq2rgb(vec3 c) {
    return vec3(c.x + 0.956 * c.y + 0.621 * c.z,
                c.x - 0.272 * c.y - 0.647 * c.z,
                c.x - 1.106 * c.y + 1.703 * c.z);
}

float luma(vec2 d) {
    return dot(samp(d), vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 d = (uToDisp * vec3(vUv, 1.0)).xy;
    float y = 1.0 - d.y;
    float lines = 240.0;
    float line = floor(y * lines);
    float frame = floor(uTime * 29.97);
    float dmg = uDamage;

    // Inestabilidad horizontal
    float jitter = (hash(vec2(line, frame)) - 0.5) * 0.0018 * (0.4 + dmg);
    jitter += sin(y * 9.0 + uTime * 1.7) * 0.0012 * dmg;

    // Banda de tracking que recorre la imagen
    float bandY = fract(uTime * 0.08 + 0.3);
    float band = (1.0 - smoothstep(0.0, 0.04 + 0.03 * dmg, abs(y - bandY))) * dmg;
    jitter += band * (hash(vec2(line, frame + 7.0)) - 0.5) * 0.06;

    // Ruido de cambio de cabezas (abajo)
    float head = smoothstep(0.955, 1.0, y);
    jitter += head * (0.015 + 0.03 * hash(vec2(line, frame + 3.0)));
    d.x += jitter;

    // Luminancia con poca resolución + halo de nitidez típico del VHS
    float lp = 1.0 / 340.0;
    float Y = luma(d + vec2(-2.0 * lp, 0.0)) * 0.12
            + luma(d + vec2(-lp, 0.0)) * 0.22
            + luma(d) * 0.32
            + luma(d + vec2(lp, 0.0)) * 0.22
            + luma(d + vec2(2.0 * lp, 0.0)) * 0.12;
    float Yw = (luma(d + vec2(-5.0 * lp, 0.0)) + luma(d + vec2(5.0 * lp, 0.0))) * 0.5;
    Y = Y + (Y - Yw) * 0.45;

    // Color muy borroso y corrido a la derecha
    vec2 C = vec2(0.0);
    float cp = 1.0 / 90.0;
    for (int i = -3; i <= 3; i++) {
        C += rgb2yiq(samp(d + vec2(float(i) * cp * 0.5 - 0.004 - 0.004 * dmg, 0.0))).yz;
    }
    C = C / 7.0 * 1.35;
    C += (vec2(hash(vec2(line * 0.5, frame)), hash(vec2(line * 0.5 + 3.0, frame))) - 0.5) * 0.03 * (0.5 + dmg);

    vec3 col = yiq2rgb(vec3(Y, C));

    // Negros levantados y blancos limitados
    col = vec3(0.055, 0.045, 0.06) + col * vec3(0.93, 0.91, 0.90);

    // Nieve
    float n = hash(d * uDispRes + vec2(frame * 13.0, frame * 7.0)) - 0.5;
    col += n * (0.05 + 0.06 * dmg);

    // Rayas blancas en la banda de tracking y abajo
    float streak = step(0.975 - 0.02 * dmg, hash(vec2(floor(d.x * 90.0), line + frame * 3.0)));
    col += vec3(streak) * (band * 0.8 + head * 0.4);
    col = mix(col, vec3(dot(col, vec3(0.3333))), band * 0.5);

    // Líneas de barrido
    col *= 0.94 + 0.06 * sin(y * lines * 6.2831);

    // Orillas más oscuras
    vec2 q = d - 0.5;
    col *= 1.0 - dot(q, q) * 0.45;

    // Textos en pantalla (PLAY, fecha, hora)
    vec4 osd = texture2D(uOsd, vec2(d.x, 1.0 - d.y));
    col = col * (1.0 - osd.a * uOsdOn) + osd.rgb * uOsdOn;

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
