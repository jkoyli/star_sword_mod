#version 150

// 星辉剑的"宇宙"着色器：顶点着色器只做三件事——
// 把模型空间坐标传给片元（球面投影要用它当视线原点）、传 UV（当遮罩）、传顶点色（光照）。
// 顶点格式是原版物品/实体用的 NEW_ENTITY，所以 UV1/UV2/Normal 必须声明，即使这里不用。
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 vPosition;
out vec2 vUv;
out vec4 vColor;

void main() {
    vPosition = Position;
    vUv = UV0;
    vColor = Color;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
