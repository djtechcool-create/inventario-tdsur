#version 100
attribute vec4 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    gl_Position = aPos;
    vUv = aUv;
}
