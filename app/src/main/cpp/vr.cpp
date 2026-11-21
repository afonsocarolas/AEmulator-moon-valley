/* Added for AEmulator Moon Valley, 2026-11-21: VR mode on Meta Quest. GPL-3.0; see LICENSE and NOTICE.md.
 *
 * An OpenXR session (the Khronos loader, OpenGL ES) that puts the running machine's screen in front of you, under the
 * night sky of Moon Valley. The controllers point at it: the trigger touches (one finger per hand), the grip carries
 * the screen around. The Kotlin side (ui/VrActivity) feeds the frames and turns what this reports into the guest's
 * input; everything here runs on its one render thread, where the EGL context is current.
 */
#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "aemu-vr", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "aemu-vr", __VA_ARGS__)

static std::string gError;

// ---------------------------------------------------------------- small math (column-major 4x4, like GL)
struct V3 { float x, y, z; };
static V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
static V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
static V3 operator*(V3 a, float s) { return {a.x * s, a.y * s, a.z * s}; }
static float dot(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
static V3 cross(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
static float len(V3 a) { return sqrtf(dot(a, a)); }
static V3 norm(V3 a) { float l = len(a); return l > 1e-6f ? a * (1.f / l) : V3{0, 0, -1}; }
static V3 v3(XrVector3f v) { return {v.x, v.y, v.z}; }
static V3 rot(XrQuaternionf q, V3 v) {
    V3 u{q.x, q.y, q.z};
    V3 t = cross(u, v) * 2.f;
    return v + t * q.w + cross(u, t);
}

struct M4 { float m[16]; };
static M4 mul(const M4 &a, const M4 &b) {
    M4 r;
    for (int c = 0; c < 4; c++)
        for (int rr = 0; rr < 4; rr++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += a.m[k * 4 + rr] * b.m[c * 4 + k];
            r.m[c * 4 + rr] = s;
        }
    return r;
}
static M4 projection(XrFovf f, float n, float fa) {
    float l = tanf(f.angleLeft), r = tanf(f.angleRight), d = tanf(f.angleDown), u = tanf(f.angleUp);
    float w = r - l, h = u - d;
    M4 p{};
    p.m[0] = 2 / w; p.m[8] = (r + l) / w;
    p.m[5] = 2 / h; p.m[9] = (u + d) / h;
    p.m[10] = -(fa + n) / (fa - n); p.m[14] = -2 * fa * n / (fa - n);
    p.m[11] = -1;
    return p;
}
/** world -> eye for an eye at [pose]; [rotOnly] leaves the position out (the sky) */
static M4 viewOf(XrPosef pose, bool rotOnly) {
    XrQuaternionf q = pose.orientation;
    V3 x = rot(q, {1, 0, 0}), y = rot(q, {0, 1, 0}), z = rot(q, {0, 0, 1});
    V3 p = rotOnly ? V3{0, 0, 0} : v3(pose.position);
    M4 v{};
    v.m[0] = x.x; v.m[4] = x.y; v.m[8] = x.z;
    v.m[1] = y.x; v.m[5] = y.y; v.m[9] = y.z;
    v.m[2] = z.x; v.m[6] = z.y; v.m[10] = z.z;
    v.m[12] = -dot(x, p); v.m[13] = -dot(y, p); v.m[14] = -dot(z, p);
    v.m[15] = 1;
    return v;
}
/** a plane's frame: local x along [r] (times hx), y along [u] (times hy), z along [n], origin at [c] */
static M4 frame(V3 c, V3 r, V3 u, V3 n, float hx, float hy) {
    M4 m{};
    m.m[0] = r.x * hx; m.m[1] = r.y * hx; m.m[2] = r.z * hx;
    m.m[4] = u.x * hy; m.m[5] = u.y * hy; m.m[6] = u.z * hy;
    m.m[8] = n.x; m.m[9] = n.y; m.m[10] = n.z;
    m.m[12] = c.x; m.m[13] = c.y; m.m[14] = c.z; m.m[15] = 1;
    return m;
}
static bool invert(const M4 &in, M4 &out) {
    const float *m = in.m;
    float inv[16];
    inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
    inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
    inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
    inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
    inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
    inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
    inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
    inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
    inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
    inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
    inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
    inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
    inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
    inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
    inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
    inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
    float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
    if (fabsf(det) < 1e-12f) return false;
    for (int i = 0; i < 16; i++) out.m[i] = inv[i] / det;
    return true;
}

// ---------------------------------------------------------------- shaders
// Every program writes linear colour: the eye swapchains are sRGB (the runtime expects that), and uEncode is set
// only when the runtime offered no sRGB format, so that the picture is not too dark there.
#define OUT_FN "uniform float uEncode;\n" \
    "vec4 outc(vec3 c, float a) { return vec4(uEncode > 0.5 ? pow(max(c, 0.0), vec3(1.0 / 2.2)) : c, a); }\n" \
    "vec3 lin(vec3 c) { return pow(c, vec3(2.2)); }\n"

static const char *VS_QUAD = R"(#version 300 es
layout(location = 0) in vec2 aCorner;
uniform mat4 uMvp;
uniform vec2 uHalf;
out vec2 vUv;
out vec2 vP;
void main() {
    vUv = vec2((aCorner.x + 1.0) * 0.5, (1.0 - aCorner.y) * 0.5);
    vP = aCorner * uHalf;
    gl_Position = uMvp * vec4(aCorner, 0.0, 1.0);
})";

// a rounded panel (or, with the radius at half its size, a dot)
static const char *FS_PANEL = "#version 300 es\nprecision highp float;\n" OUT_FN R"(
in vec2 vUv; in vec2 vP;
uniform vec2 uHalf; uniform float uRadius; uniform vec4 uColor;
out vec4 o;
void main() {
    vec2 q = abs(vP) - (uHalf - vec2(uRadius));
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
    float a = clamp(0.5 - d / max(fwidth(d), 1e-5), 0.0, 1.0) * uColor.a;
    if (a <= 0.0) discard;
    o = outc(lin(uColor.rgb), a);
})";

// the guest's screen (an sRGB texture with mipmaps, so it reads back linear) and the hint under it
static const char *FS_TEX = "#version 300 es\nprecision highp float;\n" OUT_FN R"(
in vec2 vUv; in vec2 vP;
uniform sampler2D uTex; uniform float uOpaque; uniform float uDim;
out vec4 o;
void main() {
    vec4 t = texture(uTex, vUv);
    o = outc(t.rgb * uDim, uOpaque > 0.5 ? 1.0 : t.a);
})";

// a fresh frame of the GPU bridge (an external texture) copied into the screen texture, top row first
static const char *VS_COPY = R"(#version 300 es
layout(location = 0) in vec2 aCorner;
uniform mat4 uTexM;
out vec2 vSt;
void main() {
    vSt = (uTexM * vec4((aCorner.x + 1.0) * 0.5, (1.0 - aCorner.y) * 0.5, 0.0, 1.0)).xy;
    gl_Position = vec4(aCorner, 0.0, 1.0);
})";
static const char *FS_COPY = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vSt;
uniform samplerExternalOES uTex;
out vec4 o;
void main() { o = vec4(pow(texture(uTex, vSt).rgb, vec3(2.2)), 1.0); })";

// the night over Moon Valley: stars, the moon, the hills of the valley
static const char *VS_SKY = R"(#version 300 es
layout(location = 0) in vec2 aCorner;
out vec2 vNdc;
void main() { vNdc = aCorner; gl_Position = vec4(aCorner, 0.9999, 1.0); })";
static const char *FS_SKY = "#version 300 es\nprecision highp float;\n" OUT_FN R"(
in vec2 vNdc;
uniform mat4 uInvVp;
uniform float uTime;
out vec4 o;
float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
void main() {
    vec4 w = uInvVp * vec4(vNdc, 1.0, 1.0);
    vec3 d = normalize(w.xyz / w.w);
    float el = asin(clamp(d.y, -1.0, 1.0));
    float az = atan(d.z, d.x);
    vec3 top = lin(vec3(0.020, 0.027, 0.070));
    vec3 hor = lin(vec3(0.110, 0.130, 0.250));
    vec3 c = mix(hor, top, clamp(el * 1.6, 0.0, 1.0));
    // stars: one chance per cell of a grid around you
    vec3 g = d * 140.0;
    vec3 cell = floor(g);
    float h = hash(cell);
    if (h > 0.972) {
        vec3 f = fract(g) - 0.5 - (vec3(hash(cell + 1.7), hash(cell + 3.1), hash(cell + 5.3)) - 0.5) * 0.5;
        float tw = 0.65 + 0.35 * sin(uTime * (1.0 + 3.0 * hash(cell + 9.0)) + h * 50.0);
        c += vec3(0.9, 0.92, 1.0) * smoothstep(0.16, 0.0, length(f)) * tw * (h - 0.972) * 30.0;
    }
    // the moon, with a soft halo
    vec3 md = normalize(vec3(-0.45, 0.42, -0.79));
    float cm = dot(d, md);
    c += lin(vec3(0.55, 0.60, 0.85)) * pow(max(cm, 0.0), 300.0) * 0.6;
    if (cm > 0.99935) {
        float spot = hash(floor(d * 900.0)) * 0.08 + hash(floor(d * 300.0)) * 0.10;
        c = lin(vec3(0.96, 0.94, 0.86)) * (1.0 - spot);
    }
    // the hills of the valley, dark against the sky, lit on their tops by the moon
    float hill = 0.05 + 0.035 * sin(az * 3.0 + 0.4) + 0.02 * sin(az * 7.0 + 1.3) + 0.01 * sin(az * 17.0);
    if (el < hill) {
        float rim = smoothstep(hill - 0.012, hill, el);
        c = mix(lin(vec3(0.020, 0.024, 0.045)), lin(vec3(0.10, 0.11, 0.18)), rim * 0.6);
        c = mix(c, lin(vec3(0.010, 0.012, 0.022)), clamp(-el * 2.0, 0.0, 1.0));
    }
    o = outc(c, 1.0);
})";

// rays from the controllers
static const char *VS_LINE = R"(#version 300 es
layout(location = 0) in vec4 aPos;
uniform mat4 uVp;
out float vA;
void main() { vA = aPos.w; gl_Position = uVp * vec4(aPos.xyz, 1.0); })";
static const char *FS_LINE = "#version 300 es\nprecision highp float;\n" OUT_FN R"(
in float vA;
uniform vec4 uColor;
out vec4 o;
void main() { o = outc(lin(uColor.rgb), uColor.a * vA); })";

static GLuint compile(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024]; glGetShaderInfoLog(s, sizeof log, nullptr, log);
        LOGE("shader: %s", log);
        gError = std::string("shader: ") + log;
    }
    return s;
}
static GLuint program(const char *vs, const char *fs) {
    GLuint p = glCreateProgram();
    glAttachShader(p, compile(GL_VERTEX_SHADER, vs));
    glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs));
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024]; glGetProgramInfoLog(p, sizeof log, nullptr, log);
        LOGE("program: %s", log);
        gError = std::string("program: ") + log;
    }
    return p;
}

typedef void (*PFNFBTEXMS)(GLenum, GLenum, GLenum, GLuint, GLint, GLsizei);

// ---------------------------------------------------------------- the session
struct Hand {
    bool valid = false;
    V3 o{}, d{}, r{}, u{};
    bool down = false;          // the trigger holds a finger on the screen
    float u01 = 0, v01 = 0;     // where the ray meets the screen, 0..1 from the top left
    bool onScreen = false;
    float hitT = 0;
    bool grabbing = false;
    float grabDist = 0;
};

struct Vr {
    JavaVM *jvm = nullptr;
    jobject activity = nullptr;
    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace local = XR_NULL_HANDLE, viewSpace = XR_NULL_HANDLE;
    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    bool running = false, exitNow = false, exitAsked = false;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface pbuffer = EGL_NO_SURFACE;

    XrViewConfigurationView viewCfg[2]{};
    XrSwapchain swapchain[2]{};
    std::vector<XrSwapchainImageOpenGLESKHR> images[2];
    std::vector<GLuint> fbos[2];
    int eyeW = 0, eyeH = 0;
    bool srgbOut = true;
    PFNFBTEXMS fbTexMs = nullptr;

    XrActionSet actions = XR_NULL_HANDLE;
    XrAction aAim{}, aTrigger{}, aSqueeze{}, aStick{}, aStickClick{}, aA{}, aB{}, aMenu{}, aHaptic{};
    XrPath hands[2]{};
    XrSpace aimSpace[2]{};
    Hand hand[2];

    GLuint progPanel = 0, progTex = 0, progCopy = 0, progSky = 0, progLine = 0;
    GLuint oesTex = 0, screenTex = 0, screenFbo = 0, hintTex = 0;
    int guestW = 0, guestH = 0, hintW = 0, hintH = 0;
    bool haveFrame = false;
    std::vector<uint8_t> swap;
    float aniso = 0;
    double t0 = -1, now = 0;

    // the screen: centre, facing you (normal n), half sizes in metres
    bool placed = false;
    V3 sc{0, 0, -1.6f}, sn{0, 0, 1};
    float sh = 0.6f;            // half of the longer side
    bool homeEdge = false, backEdge = false, recentsEdge = false;
    float scroll = 0;

    float halfW() const { return guestW >= guestH ? sh : sh * guestW / (float) guestH; }
    float halfH() const { return guestW >= guestH ? sh * guestH / (float) guestW : sh; }
    V3 sr() const { return norm(cross({0, 1, 0}, sn)); }
    V3 su() const { return norm(cross(sn, sr())); }
};

static bool xrFail(const char *what, XrResult r, Vr *vr) {
    char buf[256] = "";
    if (vr && vr->instance) xrResultToString(vr->instance, r, buf);
    char msg[512];
    snprintf(msg, sizeof msg, "%s failed: %s (%d)", what, buf[0] ? buf : "?", (int) r);
    gError = msg;
    LOGE("%s", msg);
    return false;
}
#define XRCHECK(call) do { XrResult r_ = (call); if (XR_FAILED(r_)) return xrFail(#call, r_, vr); } while (0)

static XrPath path(Vr *vr, const char *s) { XrPath p = XR_NULL_PATH; xrStringToPath(vr->instance, s, &p); return p; }

static bool makeAction(Vr *vr, XrAction &a, const char *name, const char *label, XrActionType type) {
    XrActionCreateInfo ci{XR_TYPE_ACTION_CREATE_INFO};
    ci.actionType = type;
    strncpy(ci.actionName, name, XR_MAX_ACTION_NAME_SIZE - 1);
    strncpy(ci.localizedActionName, label, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
    ci.countSubactionPaths = 2;
    ci.subactionPaths = vr->hands;
    XRCHECK(xrCreateAction(vr->actions, &ci, &a));
    return true;
}

static void suggest(Vr *vr, const char *profile, std::vector<std::pair<XrAction, const char *>> b) {
    std::vector<XrActionSuggestedBinding> s;
    for (auto &p : b) s.push_back({p.first, path(vr, p.second)});
    XrInteractionProfileSuggestedBinding sb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    sb.interactionProfile = path(vr, profile);
    sb.suggestedBindings = s.data();
    sb.countSuggestedBindings = (uint32_t) s.size();
    XrResult r = xrSuggestInteractionProfileBindings(vr->instance, &sb);
    if (XR_FAILED(r)) LOGI("bindings for %s not taken (%d)", profile, (int) r);
}

static bool setupActions(Vr *vr) {
    XrActionSetCreateInfo si{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(si.actionSetName, "moonvalley");
    strcpy(si.localizedActionSetName, "Moon Valley");
    XRCHECK(xrCreateActionSet(vr->instance, &si, &vr->actions));
    vr->hands[0] = path(vr, "/user/hand/left");
    vr->hands[1] = path(vr, "/user/hand/right");
    if (!makeAction(vr, vr->aAim, "aim", "Point", XR_ACTION_TYPE_POSE_INPUT)) return false;
    if (!makeAction(vr, vr->aTrigger, "touch", "Touch", XR_ACTION_TYPE_FLOAT_INPUT)) return false;
    if (!makeAction(vr, vr->aSqueeze, "grab", "Move the screen", XR_ACTION_TYPE_FLOAT_INPUT)) return false;
    if (!makeAction(vr, vr->aStick, "stick", "Scroll", XR_ACTION_TYPE_VECTOR2F_INPUT)) return false;
    if (!makeAction(vr, vr->aStickClick, "recents", "Recent apps", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!makeAction(vr, vr->aA, "home", "Home", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!makeAction(vr, vr->aB, "back", "Back", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!makeAction(vr, vr->aMenu, "leave", "Leave VR", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!makeAction(vr, vr->aHaptic, "buzz", "Buzz", XR_ACTION_TYPE_VIBRATION_OUTPUT)) return false;
    suggest(vr, "/interaction_profiles/oculus/touch_controller", {
        {vr->aAim, "/user/hand/left/input/aim/pose"}, {vr->aAim, "/user/hand/right/input/aim/pose"},
        {vr->aTrigger, "/user/hand/left/input/trigger/value"}, {vr->aTrigger, "/user/hand/right/input/trigger/value"},
        {vr->aSqueeze, "/user/hand/left/input/squeeze/value"}, {vr->aSqueeze, "/user/hand/right/input/squeeze/value"},
        {vr->aStick, "/user/hand/left/input/thumbstick"}, {vr->aStick, "/user/hand/right/input/thumbstick"},
        {vr->aStickClick, "/user/hand/left/input/thumbstick/click"}, {vr->aStickClick, "/user/hand/right/input/thumbstick/click"},
        {vr->aA, "/user/hand/left/input/x/click"}, {vr->aA, "/user/hand/right/input/a/click"},
        {vr->aB, "/user/hand/left/input/y/click"}, {vr->aB, "/user/hand/right/input/b/click"},
        {vr->aMenu, "/user/hand/left/input/menu/click"},
        {vr->aHaptic, "/user/hand/left/output/haptic"}, {vr->aHaptic, "/user/hand/right/output/haptic"},
    });
    // hand tracking (a pinch selects) and anything else that speaks the simple profile
    suggest(vr, "/interaction_profiles/khr/simple_controller", {
        {vr->aAim, "/user/hand/left/input/aim/pose"}, {vr->aAim, "/user/hand/right/input/aim/pose"},
        {vr->aTrigger, "/user/hand/left/input/select/click"}, {vr->aTrigger, "/user/hand/right/input/select/click"},
        {vr->aMenu, "/user/hand/left/input/menu/click"},
        {vr->aHaptic, "/user/hand/left/output/haptic"}, {vr->aHaptic, "/user/hand/right/output/haptic"},
    });
    XrSessionActionSetsAttachInfo ai{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    ai.countActionSets = 1;
    ai.actionSets = &vr->actions;
    XRCHECK(xrAttachSessionActionSets(vr->session, &ai));
    for (int i = 0; i < 2; i++) {
        XrActionSpaceCreateInfo ci{XR_TYPE_ACTION_SPACE_CREATE_INFO};
        ci.action = vr->aAim;
        ci.subactionPath = vr->hands[i];
        ci.poseInActionSpace.orientation.w = 1;
        XRCHECK(xrCreateActionSpace(vr->session, &ci, &vr->aimSpace[i]));
    }
    return true;
}

static bool setupEgl(Vr *vr) {
    vr->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(vr->display, nullptr, nullptr)) { gError = "eglInitialize failed"; return false; }
    const EGLint attrs[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                            EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLint n = 0;
    if (!eglChooseConfig(vr->display, attrs, &vr->config, 1, &n) || n < 1) { gError = "no EGL config for GLES 3"; return false; }
    const EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    vr->context = eglCreateContext(vr->display, vr->config, EGL_NO_CONTEXT, ctx);
    if (vr->context == EGL_NO_CONTEXT) { gError = "eglCreateContext failed"; return false; }
    const EGLint pb[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    vr->pbuffer = eglCreatePbufferSurface(vr->display, vr->config, pb);
    if (!eglMakeCurrent(vr->display, vr->pbuffer, vr->pbuffer, vr->context)) { gError = "eglMakeCurrent failed"; return false; }
    return true;
}

static void setupGl(Vr *vr) {
    vr->progPanel = program(VS_QUAD, FS_PANEL);
    vr->progTex = program(VS_QUAD, FS_TEX);
    vr->progCopy = program(VS_COPY, FS_COPY);
    vr->progSky = program(VS_SKY, FS_SKY);
    vr->progLine = program(VS_LINE, FS_LINE);
    const char *ext = (const char *) glGetString(GL_EXTENSIONS);
    if (ext && strstr(ext, "GL_EXT_texture_filter_anisotropic")) glGetFloatv(0x84FF /* MAX_TEXTURE_MAX_ANISOTROPY */, &vr->aniso);
    if (ext && strstr(ext, "GL_EXT_multisampled_render_to_texture"))
        vr->fbTexMs = (PFNFBTEXMS) eglGetProcAddress("glFramebufferTexture2DMultisampleEXT");

    glGenTextures(1, &vr->oesTex);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, vr->oesTex);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // the screen as an sRGB texture with mipmaps: sharp text up close, no shimmer from afar
    int levels = 1;
    for (int s = vr->guestW > vr->guestH ? vr->guestW : vr->guestH; s > 1; s >>= 1) levels++;
    glGenTextures(1, &vr->screenTex);
    glBindTexture(GL_TEXTURE_2D, vr->screenTex);
    glTexStorage2D(GL_TEXTURE_2D, levels, GL_SRGB8_ALPHA8, vr->guestW, vr->guestH);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (vr->aniso > 1) glTexParameterf(GL_TEXTURE_2D, 0x84FE /* TEXTURE_MAX_ANISOTROPY */, vr->aniso < 8 ? vr->aniso : 8);
    glGenFramebuffers(1, &vr->screenFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, vr->screenFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, vr->screenTex, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

static bool setupSwapchains(Vr *vr) {
    uint32_t n = 0;
    XRCHECK(xrEnumerateViewConfigurationViews(vr->instance, vr->system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &n, nullptr));
    if (n != 2) { gError = "this headset does not show two views"; return false; }
    for (auto &v : vr->viewCfg) v = {XR_TYPE_VIEW_CONFIGURATION_VIEW};
    XRCHECK(xrEnumerateViewConfigurationViews(vr->instance, vr->system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 2, &n, vr->viewCfg));
    uint32_t fc = 0;
    XRCHECK(xrEnumerateSwapchainFormats(vr->session, 0, &fc, nullptr));
    std::vector<int64_t> formats(fc);
    XRCHECK(xrEnumerateSwapchainFormats(vr->session, fc, &fc, formats.data()));
    int64_t format = 0;
    for (auto f : formats) if (f == GL_SRGB8_ALPHA8) format = f;
    if (!format) { for (auto f : formats) if (f == GL_RGBA8) format = f; vr->srgbOut = false; }
    if (!format && fc) format = formats[0];
    vr->eyeW = (int) vr->viewCfg[0].recommendedImageRectWidth;
    vr->eyeH = (int) vr->viewCfg[0].recommendedImageRectHeight;
    LOGI("eyes %dx%d, format 0x%llx, msaa %s", vr->eyeW, vr->eyeH, (long long) format, vr->fbTexMs ? "4x" : "off");
    for (int e = 0; e < 2; e++) {
        XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        ci.format = format;
        ci.sampleCount = 1;
        ci.width = (uint32_t) vr->eyeW;
        ci.height = (uint32_t) vr->eyeH;
        ci.faceCount = 1;
        ci.arraySize = 1;
        ci.mipCount = 1;
        XRCHECK(xrCreateSwapchain(vr->session, &ci, &vr->swapchain[e]));
        uint32_t ic = 0;
        XRCHECK(xrEnumerateSwapchainImages(vr->swapchain[e], 0, &ic, nullptr));
        vr->images[e].assign(ic, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        XRCHECK(xrEnumerateSwapchainImages(vr->swapchain[e], ic, &ic, (XrSwapchainImageBaseHeader *) vr->images[e].data()));
        vr->fbos[e].assign(ic, 0);
        glGenFramebuffers((GLsizei) ic, vr->fbos[e].data());
        for (uint32_t i = 0; i < ic; i++) {
            glBindFramebuffer(GL_FRAMEBUFFER, vr->fbos[e][i]);
            if (vr->fbTexMs) vr->fbTexMs(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, vr->images[e][i].image, 0, 4);
            else glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, vr->images[e][i].image, 0);
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
}

static bool start(Vr *vr, JNIEnv *env, jobject activity) {
    env->GetJavaVM(&vr->jvm);
    vr->activity = env->NewGlobalRef(activity);
    PFN_xrInitializeLoaderKHR initLoader = nullptr;
    xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction *) &initLoader);
    if (initLoader) {
        XrLoaderInitInfoAndroidKHR li{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        li.applicationVM = vr->jvm;
        li.applicationContext = vr->activity;
        initLoader((const XrLoaderInitInfoBaseHeaderKHR *) &li);
    }
    const char *exts[] = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
    XrInstanceCreateInfoAndroidKHR ai{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    ai.applicationVM = vr->jvm;
    ai.applicationActivity = vr->activity;
    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    ci.next = &ai;
    strcpy(ci.applicationInfo.applicationName, "AEmulator Moon Valley");
    ci.applicationInfo.applicationVersion = 1;
    strcpy(ci.applicationInfo.engineName, "Moon Valley VR");
    ci.applicationInfo.engineVersion = 1;
    ci.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    ci.enabledExtensionCount = 2;
    ci.enabledExtensionNames = exts;
    XRCHECK(xrCreateInstance(&ci, &vr->instance));
    XrSystemGetInfo sg{XR_TYPE_SYSTEM_GET_INFO};
    sg.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XRCHECK(xrGetSystem(vr->instance, &sg, &vr->system));
    PFN_xrGetOpenGLESGraphicsRequirementsKHR req = nullptr;
    XRCHECK(xrGetInstanceProcAddr(vr->instance, "xrGetOpenGLESGraphicsRequirementsKHR", (PFN_xrVoidFunction *) &req));
    XrGraphicsRequirementsOpenGLESKHR gr{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    XRCHECK(req(vr->instance, vr->system, &gr));
    if (!setupEgl(vr)) return false;
    setupGl(vr);
    if (!gError.empty()) return false;
    XrGraphicsBindingOpenGLESAndroidKHR gb{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    gb.display = vr->display;
    gb.config = vr->config;
    gb.context = vr->context;
    XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO};
    sci.next = &gb;
    sci.systemId = vr->system;
    XRCHECK(xrCreateSession(vr->instance, &sci, &vr->session));
    XrReferenceSpaceCreateInfo rs{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rs.poseInReferenceSpace.orientation.w = 1;
    rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    XRCHECK(xrCreateReferenceSpace(vr->session, &rs, &vr->local));
    rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    XRCHECK(xrCreateReferenceSpace(vr->session, &rs, &vr->viewSpace));
    if (!setupSwapchains(vr)) return false;
    return setupActions(vr);
}

static void pollEvents(Vr *vr) {
    XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(vr->instance, &ev) == XR_SUCCESS) {
        if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            auto *s = (XrEventDataSessionStateChanged *) &ev;
            vr->state = s->state;
            LOGI("session state %d", (int) s->state);
            if (s->state == XR_SESSION_STATE_READY) {
                XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
                bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                if (XR_SUCCEEDED(xrBeginSession(vr->session, &bi))) vr->running = true;
            } else if (s->state == XR_SESSION_STATE_STOPPING) {
                xrEndSession(vr->session);
                vr->running = false;
            } else if (s->state == XR_SESSION_STATE_EXITING || s->state == XR_SESSION_STATE_LOSS_PENDING) {
                vr->exitNow = true;
            }
        } else if (ev.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
            vr->exitNow = true;
        } else if (ev.type == XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING) {
            vr->placed = false;   // recentred: put the screen in front again
        }
        ev = {XR_TYPE_EVENT_DATA_BUFFER};
    }
}

static void buzz(Vr *vr, int i, float amp) {
    XrHapticVibration v{XR_TYPE_HAPTIC_VIBRATION};
    v.duration = 15000000;   // 15 ms
    v.frequency = XR_FREQUENCY_UNSPECIFIED;
    v.amplitude = amp;
    XrHapticActionInfo hi{XR_TYPE_HAPTIC_ACTION_INFO};
    hi.action = vr->aHaptic;
    hi.subactionPath = vr->hands[i];
    xrApplyHapticFeedback(vr->session, &hi, (const XrHapticBaseHeader *) &v);
}

static float getFloat(Vr *vr, XrAction a, int i) {
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = a; gi.subactionPath = vr->hands[i];
    XrActionStateFloat s{XR_TYPE_ACTION_STATE_FLOAT};
    return XR_SUCCEEDED(xrGetActionStateFloat(vr->session, &gi, &s)) && s.isActive ? s.currentState : 0.f;
}
static bool pressed(Vr *vr, XrAction a, int i) {
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = a; gi.subactionPath = vr->hands[i];
    XrActionStateBoolean s{XR_TYPE_ACTION_STATE_BOOLEAN};
    return XR_SUCCEEDED(xrGetActionStateBoolean(vr->session, &gi, &s)) && s.isActive && s.changedSinceLastSync && s.currentState;
}
static XrVector2f getStick(Vr *vr, int i) {
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = vr->aStick; gi.subactionPath = vr->hands[i];
    XrActionStateVector2f s{XR_TYPE_ACTION_STATE_VECTOR2F};
    if (XR_SUCCEEDED(xrGetActionStateVector2f(vr->session, &gi, &s)) && s.isActive) return s.currentState;
    return {0, 0};
}

static void place(Vr *vr, XrTime t) {
    XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(xrLocateSpace(vr->viewSpace, vr->local, t, &head))) return;
    if (!(head.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) || !(head.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) return;
    V3 f = rot(head.pose.orientation, {0, 0, -1});
    f.y = 0;
    f = norm(f);
    V3 hp = v3(head.pose.position);
    vr->sc = hp + f * 1.6f + V3{0, -0.08f, 0};
    vr->sn = f * -1.f;
    vr->placed = true;
}

/** pointing, touching, carrying the screen; the buttons */
static void input(Vr *vr, XrTime t, float dt) {
    bool focused = vr->state == XR_SESSION_STATE_FOCUSED;
    if (!focused) {
        for (auto &h : vr->hand) { h.valid = false; h.down = false; h.grabbing = false; }
        return;
    }
    XrActiveActionSet as{vr->actions, XR_NULL_PATH};
    XrActionsSyncInfo si{XR_TYPE_ACTIONS_SYNC_INFO};
    si.countActiveActionSets = 1;
    si.activeActionSets = &as;
    xrSyncActions(vr->session, &si);

    XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
    xrLocateSpace(vr->viewSpace, vr->local, t, &head);
    V3 hp = v3(head.pose.position);

    float hw = vr->halfW(), hh = vr->halfH();
    vr->scroll = 0;
    for (int i = 0; i < 2; i++) {
        Hand &h = vr->hand[i];
        XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
        h.valid = XR_SUCCEEDED(xrLocateSpace(vr->aimSpace[i], vr->local, t, &loc)) &&
                  (loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) && (loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT);
        if (!h.valid) { h.down = false; h.grabbing = false; h.onScreen = false; continue; }
        h.o = v3(loc.pose.position);
        h.d = norm(rot(loc.pose.orientation, {0, 0, -1}));
        h.r = rot(loc.pose.orientation, {1, 0, 0});
        h.u = rot(loc.pose.orientation, {0, 1, 0});

        float squeeze = getFloat(vr, vr->aSqueeze, i);
        XrVector2f stick = getStick(vr, i);
        // grip: the screen hangs on the end of the ray and turns to face you
        if (!h.grabbing && squeeze > 0.6f && !h.down) {
            h.grabbing = true;
            h.grabDist = len(vr->sc - h.o);
            if (h.grabDist < 0.3f) h.grabDist = 0.3f;
            buzz(vr, i, 0.3f);
        } else if (h.grabbing && squeeze < 0.4f) {
            h.grabbing = false;
        }
        if (h.grabbing) {
            // stick forward/back: farther/nearer; left/right: smaller/bigger
            h.grabDist += stick.y * dt * 1.2f;
            if (h.grabDist < 0.3f) h.grabDist = 0.3f;
            if (h.grabDist > 6.f) h.grabDist = 6.f;
            vr->sh *= 1.f + stick.x * dt * 0.8f;
            if (vr->sh < 0.15f) vr->sh = 0.15f;
            if (vr->sh > 3.f) vr->sh = 3.f;
            vr->sc = h.o + h.d * h.grabDist;
            V3 toHead = hp - vr->sc;
            toHead.y = 0;
            if (len(toHead) > 0.05f) vr->sn = norm(toHead);
            hw = vr->halfW(); hh = vr->halfH();
        } else if (fabsf(stick.y) > fabsf(vr->scroll)) {
            vr->scroll = stick.y;
        }

        // where the ray meets the screen
        float denom = dot(h.d, vr->sn);
        h.onScreen = false;
        if (denom < -1e-4f) {
            float tt = dot(vr->sc - h.o, vr->sn) / denom;
            if (tt > 0) {
                V3 p = h.o + h.d * tt - vr->sc;
                float x = dot(p, vr->sr()) / hw, y = dot(p, vr->su()) / hh;
                h.hitT = tt;
                if (h.down || (fabsf(x) <= 1 && fabsf(y) <= 1)) {
                    h.onScreen = fabsf(x) <= 1 && fabsf(y) <= 1;
                    x = x < -1 ? -1 : x > 1 ? 1 : x;
                    y = y < -1 ? -1 : y > 1 ? 1 : y;
                    h.u01 = (x + 1) * 0.5f;
                    h.v01 = (1 - y) * 0.5f;
                }
            }
        }
        float trig = getFloat(vr, vr->aTrigger, i);
        if (!h.down && trig > 0.7f && h.onScreen && !h.grabbing) { h.down = true; buzz(vr, i, 0.25f); }
        else if (h.down && trig < 0.4f) h.down = false;

        if (pressed(vr, vr->aA, i)) vr->homeEdge = true;
        if (pressed(vr, vr->aB, i)) vr->backEdge = true;
        if (pressed(vr, vr->aStickClick, i)) vr->recentsEdge = true;
        if (pressed(vr, vr->aMenu, i) && !vr->exitAsked) { vr->exitAsked = true; xrRequestExitSession(vr->session); }
    }
}

static const float QUAD[] = {-1, -1, 1, -1, -1, 1, 1, 1};

static void drawQuad(GLuint prog, const M4 &mvp, float hx, float hy) {
    glUniformMatrix4fv(glGetUniformLocation(prog, "uMvp"), 1, GL_FALSE, mvp.m);
    glUniform2f(glGetUniformLocation(prog, "uHalf"), hx, hy);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, QUAD);
    glEnableVertexAttribArray(0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

static void panel(Vr *vr, const M4 &vp, V3 c, float hx, float hy, float radius, float r, float g, float b, float a) {
    glUseProgram(vr->progPanel);
    glUniform1f(glGetUniformLocation(vr->progPanel, "uEncode"), vr->srgbOut ? 0.f : 1.f);
    glUniform1f(glGetUniformLocation(vr->progPanel, "uRadius"), radius);
    glUniform4f(glGetUniformLocation(vr->progPanel, "uColor"), r, g, b, a);
    drawQuad(vr->progPanel, mul(vp, frame(c, vr->sr(), vr->su(), vr->sn, hx, hy)), hx, hy);
}

static void renderEye(Vr *vr, const XrView &view, int eye) {
    M4 proj = projection(view.fov, 0.05f, 100.f);
    M4 vp = mul(proj, viewOf(view.pose, false));
    float enc = vr->srgbOut ? 0.f : 1.f;
    glViewport(0, 0, vr->eyeW, vr->eyeH);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);

    // the sky
    M4 skyVp = mul(proj, viewOf(view.pose, true)), inv;
    invert(skyVp, inv);
    glUseProgram(vr->progSky);
    glUniform1f(glGetUniformLocation(vr->progSky, "uEncode"), enc);
    glUniformMatrix4fv(glGetUniformLocation(vr->progSky, "uInvVp"), 1, GL_FALSE, inv.m);
    glUniform1f(glGetUniformLocation(vr->progSky, "uTime"), (float) fmod(vr->now, 1000.0));
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, QUAD);
    glEnableVertexAttribArray(0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    float hw = vr->halfW(), hh = vr->halfH();
    V3 back = vr->sn * -0.004f;
    // the phone's body around the screen, a little behind it
    float rim = 0.035f * (vr->sh / 0.6f);
    panel(vr, vp, vr->sc + back * 2.f, hw + rim + 0.004f, hh + rim + 0.004f, rim * 1.4f, 0.30f, 0.33f, 0.50f, 0.55f);
    panel(vr, vp, vr->sc + back, hw + rim, hh + rim, rim * 1.2f, 0.035f, 0.040f, 0.070f, 1.f);
    // the screen
    if (vr->haveFrame) {
        glUseProgram(vr->progTex);
        glUniform1f(glGetUniformLocation(vr->progTex, "uEncode"), enc);
        glUniform1f(glGetUniformLocation(vr->progTex, "uOpaque"), 1.f);
        glUniform1f(glGetUniformLocation(vr->progTex, "uDim"), 1.f);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, vr->screenTex);
        glUniform1i(glGetUniformLocation(vr->progTex, "uTex"), 0);
        drawQuad(vr->progTex, mul(vp, frame(vr->sc, vr->sr(), vr->su(), vr->sn, hw, hh)), hw, hh);
    } else {
        panel(vr, vp, vr->sc, hw, hh, 0.f, 0.f, 0.f, 0.f, 1.f);
    }
    // the hint under the screen
    if (vr->hintTex) {
        float w = 0.30f * (vr->sh / 0.6f) + 0.15f, h = w * vr->hintH / (float) vr->hintW;
        V3 c = vr->sc - vr->su() * (hh + rim + 0.03f + h) - back * 4.f;
        glUseProgram(vr->progTex);
        glUniform1f(glGetUniformLocation(vr->progTex, "uEncode"), enc);
        glUniform1f(glGetUniformLocation(vr->progTex, "uOpaque"), 0.f);
        glUniform1f(glGetUniformLocation(vr->progTex, "uDim"), 0.9f);
        glBindTexture(GL_TEXTURE_2D, vr->hintTex);
        glUniform1i(glGetUniformLocation(vr->progTex, "uTex"), 0);
        drawQuad(vr->progTex, mul(vp, frame(c, vr->sr(), vr->su(), vr->sn, w, h)), w, h);
    }
    // rays and the dots where they land
    for (int i = 0; i < 2; i++) {
        const Hand &h = vr->hand[i];
        if (!h.valid) continue;
        bool hits = h.onScreen || h.down;
        float L = hits ? h.hitT : 2.5f;
        float w = 0.0022f;
        V3 o = h.o, e = h.o + h.d * L;
        V3 a1 = h.r * w, a2 = h.u * w;
        float fade = hits ? 0.9f : 0.0f;
        float pts[] = {
            o.x - a1.x, o.y - a1.y, o.z - a1.z, 0.9f, o.x + a1.x, o.y + a1.y, o.z + a1.z, 0.9f,
            e.x - a1.x, e.y - a1.y, e.z - a1.z, fade, e.x + a1.x, e.y + a1.y, e.z + a1.z, fade,
            o.x - a2.x, o.y - a2.y, o.z - a2.z, 0.9f, o.x + a2.x, o.y + a2.y, o.z + a2.z, 0.9f,
            e.x - a2.x, e.y - a2.y, e.z - a2.z, fade, e.x + a2.x, e.y + a2.y, e.z + a2.z, fade,
        };
        glUseProgram(vr->progLine);
        glUniform1f(glGetUniformLocation(vr->progLine, "uEncode"), enc);
        glUniformMatrix4fv(glGetUniformLocation(vr->progLine, "uVp"), 1, GL_FALSE, vp.m);
        if (h.grabbing) glUniform4f(glGetUniformLocation(vr->progLine, "uColor"), 0.98f, 0.82f, 0.45f, 0.8f);
        else glUniform4f(glGetUniformLocation(vr->progLine, "uColor"), 0.78f, 0.84f, 1.0f, 0.6f);
        glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 0, pts);
        glEnableVertexAttribArray(0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDrawArrays(GL_TRIANGLE_STRIP, 4, 4);
        if (hits) {
            float x = (h.u01 * 2 - 1) * hw, y = (1 - h.v01 * 2) * hh;
            V3 p = vr->sc + vr->sr() * x + vr->su() * y + vr->sn * 0.003f;
            float rr = (h.down ? 0.008f : 0.011f) * (0.5f + h.hitT * 0.35f);
            panel(vr, vp, p, rr * 1.35f, rr * 1.35f, rr * 1.35f, 0.05f, 0.06f, 0.12f, 0.55f);
            if (h.down) panel(vr, vp, p, rr, rr, rr, 0.98f, 0.82f, 0.45f, 1.f);
            else panel(vr, vp, p, rr, rr, rr, 0.95f, 0.96f, 1.0f, 0.95f);
        }
    }
    (void) eye;
}

/** one frame: 0 go on, 1 leave VR */
static int frameOnce(Vr *vr, float *out) {
    pollEvents(vr);
    if (vr->exitNow) return 1;
    if (!vr->running) { usleep(10000); return 0; }
    XrFrameWaitInfo wi{XR_TYPE_FRAME_WAIT_INFO};
    XrFrameState fs{XR_TYPE_FRAME_STATE};
    if (XR_FAILED(xrWaitFrame(vr->session, &wi, &fs))) { usleep(5000); return 0; }
    XrFrameBeginInfo bi{XR_TYPE_FRAME_BEGIN_INFO};
    if (XR_FAILED(xrBeginFrame(vr->session, &bi))) return 0;
    double tNow = fs.predictedDisplayTime / 1e9;
    if (vr->t0 < 0) vr->t0 = tNow;
    float dt = (float) (tNow - (vr->t0 + vr->now));
    if (dt < 0 || dt > 0.1f) dt = 0.014f;
    vr->now = tNow - vr->t0;
    if (!vr->placed) place(vr, fs.predictedDisplayTime);
    input(vr, fs.predictedDisplayTime, dt);

    XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    XrViewLocateInfo li{XR_TYPE_VIEW_LOCATE_INFO};
    li.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    li.displayTime = fs.predictedDisplayTime;
    li.space = vr->local;
    XrViewState vs{XR_TYPE_VIEW_STATE};
    uint32_t vc = 0;
    bool viewsOk = XR_SUCCEEDED(xrLocateViews(vr->session, &li, &vs, 2, &vc, views)) && vc == 2 &&
                   (vs.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT);
    XrCompositionLayerProjectionView pv[2] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
    bool rendered = false;
    if (fs.shouldRender && viewsOk) {
        for (int e = 0; e < 2; e++) {
            uint32_t idx = 0;
            XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
            xrAcquireSwapchainImage(vr->swapchain[e], &ai, &idx);
            XrSwapchainImageWaitInfo wi2{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
            wi2.timeout = XR_INFINITE_DURATION;
            xrWaitSwapchainImage(vr->swapchain[e], &wi2);
            glBindFramebuffer(GL_FRAMEBUFFER, vr->fbos[e][idx]);
            renderEye(vr, views[e], e);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            xrReleaseSwapchainImage(vr->swapchain[e], &ri);
            pv[e].pose = views[e].pose;
            pv[e].fov = views[e].fov;
            pv[e].subImage.swapchain = vr->swapchain[e];
            pv[e].subImage.imageRect.offset = {0, 0};
            pv[e].subImage.imageRect.extent = {vr->eyeW, vr->eyeH};
        }
        rendered = true;
    }
    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    layer.space = vr->local;
    layer.viewCount = 2;
    layer.views = pv;
    const XrCompositionLayerBaseHeader *layers[] = {(const XrCompositionLayerBaseHeader *) &layer};
    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
    ei.displayTime = fs.predictedDisplayTime;
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    ei.layerCount = rendered ? 1 : 0;
    ei.layers = layers;
    xrEndFrame(vr->session, &ei);

    // to the Kotlin side: [0] focused, per hand (left 1..3, right 4..6) down/u/v, [7] home, [8] back, [9] recents, [10] scroll
    out[0] = vr->state == XR_SESSION_STATE_FOCUSED ? 1.f : 0.f;
    for (int i = 0; i < 2; i++) {
        out[1 + i * 3] = vr->hand[i].down ? 1.f : 0.f;
        out[2 + i * 3] = vr->hand[i].u01;
        out[3 + i * 3] = vr->hand[i].v01;
    }
    out[7] = vr->homeEdge ? 1.f : 0.f;
    out[8] = vr->backEdge ? 1.f : 0.f;
    out[9] = vr->recentsEdge ? 1.f : 0.f;
    out[10] = vr->scroll;
    vr->homeEdge = vr->backEdge = vr->recentsEdge = false;
    return 0;
}

static void destroy(Vr *vr, JNIEnv *env) {
    for (auto &s : vr->aimSpace) if (s) xrDestroySpace(s);
    if (vr->actions) xrDestroyActionSet(vr->actions);
    for (auto &s : vr->swapchain) if (s) xrDestroySwapchain(s);
    if (vr->viewSpace) xrDestroySpace(vr->viewSpace);
    if (vr->local) xrDestroySpace(vr->local);
    if (vr->session) xrDestroySession(vr->session);
    if (vr->instance) xrDestroyInstance(vr->instance);
    if (vr->display != EGL_NO_DISPLAY) {
        eglMakeCurrent(vr->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (vr->pbuffer != EGL_NO_SURFACE) eglDestroySurface(vr->display, vr->pbuffer);
        if (vr->context != EGL_NO_CONTEXT) eglDestroyContext(vr->display, vr->context);
    }
    if (vr->activity) env->DeleteGlobalRef(vr->activity);
    delete vr;
}

// ---------------------------------------------------------------- JNI (app.aemu.core.VrNative)
extern "C" {

JNIEXPORT jlong JNICALL Java_app_aemu_core_VrNative_create(JNIEnv *env, jclass, jobject activity, jint w, jint h) {
    gError.clear();
    Vr *vr = new Vr();
    vr->guestW = w > 0 ? w : 1;
    vr->guestH = h > 0 ? h : 1;
    if (!start(vr, env, activity)) {
        if (gError.empty()) gError = "VR could not start";
        destroy(vr, env);
        return 0;
    }
    return (jlong) (intptr_t) vr;
}

JNIEXPORT jstring JNICALL Java_app_aemu_core_VrNative_lastError(JNIEnv *env, jclass) {
    return env->NewStringUTF(gError.c_str());
}

JNIEXPORT jint JNICALL Java_app_aemu_core_VrNative_oesTexture(JNIEnv *, jclass, jlong h) {
    return (jint) ((Vr *) (intptr_t) h)->oesTex;
}

/** a fresh frame of the GPU bridge (SurfaceTexture.updateTexImage done, [m] its transform) into the screen */
JNIEXPORT void JNICALL Java_app_aemu_core_VrNative_oesFrame(JNIEnv *env, jclass, jlong h, jfloatArray m) {
    Vr *vr = (Vr *) (intptr_t) h;
    float tm[16];
    env->GetFloatArrayRegion(m, 0, 16, tm);
    glBindFramebuffer(GL_FRAMEBUFFER, vr->screenFbo);
    glViewport(0, 0, vr->guestW, vr->guestH);
    glDisable(GL_BLEND);
    glUseProgram(vr->progCopy);
    glUniformMatrix4fv(glGetUniformLocation(vr->progCopy, "uTexM"), 1, GL_FALSE, tm);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, vr->oesTex);
    glUniform1i(glGetUniformLocation(vr->progCopy, "uTex"), 0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, QUAD);
    glEnableVertexAttribArray(0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, vr->screenTex);
    glGenerateMipmap(GL_TEXTURE_2D);
    vr->haveFrame = true;
}

static void argbToRgba(const jint *src, uint8_t *dst, size_t n, bool opaque) {
    for (size_t i = 0; i < n; i++) {
        uint32_t c = (uint32_t) src[i];
        dst[i * 4] = (uint8_t) (c >> 16);
        dst[i * 4 + 1] = (uint8_t) (c >> 8);
        dst[i * 4 + 2] = (uint8_t) c;
        dst[i * 4 + 3] = opaque ? 255 : (uint8_t) (c >> 24);
    }
}

/** a frame read from fb0 (ARGB, the guest's size) into the screen */
JNIEXPORT void JNICALL Java_app_aemu_core_VrNative_upload(JNIEnv *env, jclass, jlong h, jintArray px, jint w, jint ht) {
    Vr *vr = (Vr *) (intptr_t) h;
    if (w != vr->guestW || ht != vr->guestH || env->GetArrayLength(px) < w * ht) return;
    size_t n = (size_t) w * ht;
    vr->swap.resize(n * 4);
    jint *src = (jint *) env->GetPrimitiveArrayCritical(px, nullptr);
    if (!src) return;
    argbToRgba(src, vr->swap.data(), n, true);
    env->ReleasePrimitiveArrayCritical(px, src, JNI_ABORT);
    glBindTexture(GL_TEXTURE_2D, vr->screenTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, ht, GL_RGBA, GL_UNSIGNED_BYTE, vr->swap.data());
    glGenerateMipmap(GL_TEXTURE_2D);
    vr->haveFrame = true;
}

/** the hint under the screen (ARGB with alpha) */
JNIEXPORT void JNICALL Java_app_aemu_core_VrNative_setHint(JNIEnv *env, jclass, jlong h, jintArray px, jint w, jint ht) {
    Vr *vr = (Vr *) (intptr_t) h;
    if (w <= 0 || ht <= 0 || env->GetArrayLength(px) < w * ht) return;
    std::vector<jint> src((size_t) w * ht);
    env->GetIntArrayRegion(px, 0, w * ht, src.data());
    std::vector<uint8_t> rgba((size_t) w * ht * 4);
    argbToRgba(src.data(), rgba.data(), src.size(), false);
    if (!vr->hintTex) glGenTextures(1, &vr->hintTex);
    glBindTexture(GL_TEXTURE_2D, vr->hintTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, w, ht, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    glGenerateMipmap(GL_TEXTURE_2D);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    vr->hintW = w;
    vr->hintH = ht;
}

JNIEXPORT jint JNICALL Java_app_aemu_core_VrNative_frame(JNIEnv *env, jclass, jlong h, jfloatArray out) {
    Vr *vr = (Vr *) (intptr_t) h;
    float o[11] = {0};
    int r = frameOnce(vr, o);
    env->SetFloatArrayRegion(out, 0, 11, o);
    return r;
}

/** leave VR from outside (the activity is going away): the runtime then ends the session */
JNIEXPORT void JNICALL Java_app_aemu_core_VrNative_requestExit(JNIEnv *, jclass, jlong h) {
    Vr *vr = (Vr *) (intptr_t) h;
    if (vr->running && !vr->exitAsked) { vr->exitAsked = true; xrRequestExitSession(vr->session); }
    else if (!vr->running) vr->exitNow = true;
}

JNIEXPORT void JNICALL Java_app_aemu_core_VrNative_destroy(JNIEnv *env, jclass, jlong h) {
    destroy((Vr *) (intptr_t) h, env);
}

}
