
#if defined(__ANDROID__)
#include <android/log.h>
#endif
#include "endxr_openxr_runtime.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <condition_variable>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <limits>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#if defined(__ANDROID__)
#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <sys/resource.h>
#include <unistd.h>
#else
using EGLDisplay = void*;
using EGLConfig = void*;
using EGLContext = void*;
using EGLSurface = void*;
using EGLint = int;
using GLuint = std::uint32_t;
using GLenum = std::uint32_t;
using GLint = int;
using GLsizei = int;
using GLbitfield = std::uint32_t;
using GLboolean = unsigned char;
constexpr EGLint EGL_DRAW = 0x3059;
constexpr EGLint EGL_WIDTH = 0x3057;
constexpr EGLint EGL_HEIGHT = 0x3056;
constexpr EGLint EGL_FALSE = 0;
constexpr GLenum GL_TEXTURE_2D = 0x0DE1;
constexpr GLenum GL_TEXTURE_2D_ARRAY = 0x8C1A;
constexpr GLenum GL_SRGB8_ALPHA8 = 0x8C43;
constexpr GLenum GL_DEPTH24_STENCIL8 = 0x88F0;
constexpr GLenum GL_DEPTH_COMPONENT24 = 0x81A6;
constexpr GLenum GL_DEPTH_COMPONENT16 = 0x81A5;
constexpr GLenum GL_RGBA8 = 0x8058;
constexpr GLenum GL_RGBA = 0x1908;
constexpr GLenum GL_UNSIGNED_BYTE = 0x1401;
constexpr GLenum GL_TEXTURE_MIN_FILTER = 0x2801;
constexpr GLenum GL_TEXTURE_MAG_FILTER = 0x2800;
constexpr GLenum GL_LINEAR = 0x2601;
constexpr GLenum GL_TEXTURE_WRAP_S = 0x2802;
constexpr GLenum GL_TEXTURE_WRAP_T = 0x2803;
constexpr GLenum GL_CLAMP_TO_EDGE = 0x812F;
constexpr GLenum GL_FRAMEBUFFER = 0x8D40;
constexpr GLenum GL_READ_FRAMEBUFFER = 0x8CA8;
constexpr GLenum GL_DRAW_FRAMEBUFFER = 0x8CA9;
constexpr GLenum GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
constexpr GLenum GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
constexpr GLenum GL_COLOR_ATTACHMENT0 = 0x8CE0;
constexpr GLenum GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
constexpr GLenum GL_SCISSOR_TEST = 0x0C11;
constexpr GLenum GL_COLOR_CLEAR_VALUE = 0x0C22;
constexpr GLenum GL_COLOR_WRITEMASK = 0x0C23;
constexpr GLenum GL_COLOR_BUFFER_BIT = 0x00004000;
constexpr GLenum GL_NO_ERROR = 0;
constexpr GLenum GL_TEXTURE = 0x1702;
constexpr GLenum GL_VIEWPORT = 0x0BA2;
#endif

struct XrControllerInputSample {
    bool active = false;
    float trigger = 0.0f;
    float grip = 0.0f;
    float stickX = 0.0f;
    float stickY = 0.0f;
    std::uint32_t buttons = 0;
    bool poseActive = false;
    XrPosef gripPose{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
    bool handJointsActive = false;
    std::array<float, XR_HAND_JOINT_COUNT_EXT * 8> handJoints{};
};

struct XrActionStateKey {
    XrAction action = XR_NULL_HANDLE;
    XrPath subactionPath = XR_NULL_PATH;
    bool operator==(const XrActionStateKey& other) const {
        return action == other.action && subactionPath == other.subactionPath;
    }
};

struct XrActionStateKeyHash {
    std::size_t operator()(const XrActionStateKey& key) const {
        const std::size_t actionHash = std::hash<XrAction>{}(key.action);
        const std::size_t pathHash = std::hash<XrPath>{}(key.subactionPath);
        return actionHash ^ (pathHash + 0x9e3779b9u +
            (actionHash << 6u) + (actionHash >> 2u));
    }
};

struct XrActionStateSnapshot {
    bool active = false;
    bool booleanValue = false;
    float scalarValue = 0.0f;
    XrVector2f vectorValue{0.0f, 0.0f};
    bool booleanChanged = false;
    bool scalarChanged = false;
    bool vectorChanged = false;
    XrTime booleanLastChangeTime = 0;
    XrTime scalarLastChangeTime = 0;
    XrTime vectorLastChangeTime = 0;
};

struct PendingRuntimeTelemetry {
    std::string eventName;
    std::uint64_t sessionId = 0;
    std::uint64_t frameCount = 0;
    std::string traceStage;
    std::string traceOperation;
    std::string traceResult;
    std::string traceDetails;
    std::int32_t traceResultCode = 0;
    std::uint64_t traceDurationNs = 0;
    std::uint64_t traceFrameIndex = 0;
    std::uint64_t traceThreadId = 0;
    std::uint64_t traceMonotonicNs = 0;
};

struct XrInstance_T {
    XrVersion requestedApiVersion = XR_MAKE_VERSION(1, 0, 0);
    std::uint64_t telemetryId = 0;
#if defined(__ANDROID__)
    JavaVM* applicationVm = nullptr;
    jobject applicationActivity = nullptr;
    std::atomic<bool> stopExternalInputPollingRequested{false};
    std::mutex externalInputWakeMutex;
    std::condition_variable externalInputWake;
    std::thread externalInputPollThread;
    std::mutex runtimeTelemetryMutex;
    std::deque<PendingRuntimeTelemetry> pendingRuntimeTelemetry;
    std::chrono::steady_clock::time_point runtimeTelemetryRetryAt{};
    std::atomic<bool> runtimeTelemetryUnavailableLogged{false};
    std::atomic<std::uint64_t> externalInputPollCount{0};
#else
    void* applicationVm = nullptr;
    void* applicationActivity = nullptr;
#endif
    bool androidExtensionEnabled = false;
    bool glesExtensionEnabled = false;
    bool handTrackingExtensionEnabled = false;
    bool handInteractionExtensionEnabled = false;
    bool androidThreadSettingsExtensionEnabled = false;
    bool compositionLayerDepthExtensionEnabled = false;
    bool convertTimespecTimeExtensionEnabled = false;
    bool locateSpacesExtensionEnabled = false;
    bool performanceSettingsExtensionEnabled = false;
    bool localFloorExtensionEnabled = false;
    bool displayRefreshRateExtensionEnabled = false;
    bool colorSpaceExtensionEnabled = false;
    bool glesRequirementsQueried = false;
#if defined(__ANDROID__)
    bool cursorColumnsCached = false;
    std::array<jint, 40> cursorColumnIndices{};
#endif
    std::mutex eventMutex;
    std::deque<XrEventDataBuffer> pendingSessionEvents;
    std::unordered_map<std::string, XrPath> pathHandles;
    std::unordered_map<XrPath, std::string> pathStrings;
    std::unordered_map<XrPath, std::vector<XrActionSuggestedBinding>> suggestedBindings;
    XrPath nextPath = 1;
};

struct XrSession_T {
    XrInstance owner = XR_NULL_HANDLE;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    EGLDisplay display = nullptr;
    EGLConfig config = nullptr;
    EGLContext context = nullptr;
    XrSessionState state = XR_SESSION_STATE_IDLE;
    bool running = false;
    bool exitRequested = false;
    bool frameWaited = false;
    bool frameBegun = false;
    bool hasPredictedTime = false;
    XrTime predictedDisplayTime = 0;
    XrDuration predictedDisplayPeriod = 0;
    std::unordered_set<XrSwapchain> swapchains;
    std::unordered_set<XrSpace> spaces;
    std::unordered_set<XrHandTrackerEXT> handTrackers;
    std::uint64_t telemetryId = 0;
    std::uint64_t telemetryFrameCount = 0;
    std::uint64_t diagnosticFrameIndex = 0;
    bool actionSetsAttached = false;
    bool actionSyncCompleted = false;
    std::unordered_set<XrActionSet> attachedActionSets;
    std::vector<XrActiveActionSet> activeActionSets;
    std::array<XrControllerInputSample, 2> synchronizedControllerInputs{};
    std::unordered_map<XrActionStateKey, XrActionStateSnapshot, XrActionStateKeyHash>
        actionStateSnapshots;
    GLuint compositorFramebuffer = 0;
    float displayRefreshRateHz = 60.0f;
    XrColorSpaceFB colorSpace = XR_COLOR_SPACE_REC709_FB;
};

struct XrSwapchain_T {
    XrSession owner = XR_NULL_HANDLE;
    std::int64_t format = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t arraySize = 1;
    std::uint32_t imageCount = 3;
    std::array<GLuint, 3> images{};
    std::uint32_t nextAcquire = 0;
    std::uint32_t acquiredIndex = std::numeric_limits<std::uint32_t>::max();
    std::uint32_t lastReleasedIndex = std::numeric_limits<std::uint32_t>::max();
    bool imageWaited = false;
    bool isDepth = false;
    bool fboValidated = false;
};

struct XrSpace_T {
    XrSession owner = XR_NULL_HANDLE;
    XrReferenceSpaceType type = XR_REFERENCE_SPACE_TYPE_LOCAL;
    XrPosef poseInReferenceSpace{};
    bool isActionSpace = false;
    XrAction action = XR_NULL_HANDLE;
    XrPath actionSubactionPath = XR_NULL_PATH;
};

struct XrHandTracker_T {
    XrSession owner = XR_NULL_HANDLE;
    XrHandEXT hand = XR_HAND_LEFT_EXT;
};

struct XrActionSet_T {
    XrInstance owner = XR_NULL_HANDLE;
    std::string name;
    std::string localizedName;
    std::uint32_t priority = 0;
    XrSession attachedSession = XR_NULL_HANDLE;
    std::unordered_set<XrAction> actions;
};

struct XrAction_T {
    XrActionSet owner = XR_NULL_HANDLE;
    std::string name;
    std::string localizedName;
    XrActionType type = XR_ACTION_TYPE_BOOLEAN_INPUT;
    std::vector<XrPath> subactionPaths;
    std::unordered_set<XrSpace> spaces;
};

namespace {
#if defined(__ANDROID__)
bool startExternalControllerInputPolling(XrInstance_T* instance);
void stopExternalControllerInputPolling(XrInstance_T* instance);
void enqueueRuntimeTelemetry(
    XrInstance_T* instance,
    const char* eventName,
    std::uint64_t sessionId = 0,
    std::uint64_t frameCount = 0
);
void enqueueRuntimeTrace(
    XrInstance_T* instance,
    std::uint64_t sessionId,
    const char* stage,
    const char* operation,
    XrResult result,
    std::uint64_t durationNs,
    std::uint64_t frameIndex,
    const char* details
);
bool flushRuntimeTelemetryImpl(XrInstance_T* instance);
const char* openXrTraceResultName(XrResult result);
#endif
bool locateEstimatedHandPose(XrSpace_T* object, XrSpace_T* base, XrPosef* pose);
XrPosef composePose(const XrPosef& parent, const XrPosef& child);
XrPosef inversePose(const XrPosef& value);
constexpr XrSystemId kEndXrSystemId = 1;
constexpr std::uint32_t kRecommendedEyeWidth = 960;
constexpr std::uint32_t kRecommendedEyeHeight = 1080;
constexpr std::uint32_t kMaximumEyeWidth = 2048;
constexpr std::uint32_t kMaximumEyeHeight = 2048;
constexpr std::uint32_t kEndXrVendorId = 0x454E4458; // "ENDX"
constexpr XrVersion kMinEndXrApiVersion = XR_MAKE_VERSION(1, 0, 0);
constexpr XrVersion kEndXrApiVersion = XR_MAKE_VERSION(1, 1, 65);
bool isSupportedApiVersion(XrVersion requested) {
    return XR_VERSION_MAJOR(requested) == 1 && XR_VERSION_MINOR(requested) <= 1;
}
constexpr float kDefaultStageEyeHeightMeters = 1.65f;
constexpr std::uint32_t kMaxSupportedLayers = 16;
constexpr std::array<float, 4> kSupportedRefreshRatesHz{{60.0f, 72.0f, 90.0f, 120.0f}};
constexpr std::array<XrColorSpaceFB, 3> kSupportedColorSpacesFB{{
    XR_COLOR_SPACE_REC709_FB,
    XR_COLOR_SPACE_QUEST_FB,
    XR_COLOR_SPACE_REC2020_FB
}};

XrDuration sessionFramePeriodNs(const XrSession_T* session) {
    const float hz = (session != nullptr && session->displayRefreshRateHz > 1.0f)
        ? session->displayRefreshRateHz : 60.0f;
    return static_cast<XrDuration>(1000000000.0 / static_cast<double>(hz) + 0.5);
}

bool isColorSwapchainFormat(std::int64_t format) {
    return format == static_cast<std::int64_t>(GL_RGBA8) ||
        format == static_cast<std::int64_t>(GL_SRGB8_ALPHA8);
}

bool isDepthSwapchainFormat(std::int64_t format) {
    return format == static_cast<std::int64_t>(GL_DEPTH24_STENCIL8) ||
        format == static_cast<std::int64_t>(GL_DEPTH_COMPONENT24) ||
        format == static_cast<std::int64_t>(GL_DEPTH_COMPONENT16);
}
constexpr float kHalfIpdMeters = 0.032f;
constexpr const char* kRuntimeName = "EndXR Phone Runtime";
constexpr const char* kSystemName = "EndXR Phone VR";

std::mutex g_instanceMutex;
std::unordered_map<XrInstance, XrInstance_T*> g_instances;
// Seed identities with the process-local monotonic clock so a second game
// process cannot inherit the previous process's persisted telemetry instance id.
std::atomic<std::uint64_t> g_nextTelemetryIdentity{
    static_cast<std::uint64_t>(std::chrono::steady_clock::now().time_since_epoch().count())
};
#if XR_PTR_SIZE != 8
std::uint64_t g_nextInstanceHandle = 1;
#endif

std::mutex g_sessionMutex;
std::unordered_map<XrSession, XrSession_T*> g_sessions;
#if XR_PTR_SIZE != 8
std::uint64_t g_nextSessionHandle = 1;
#endif

std::mutex g_swapchainMutex;
std::unordered_map<XrSwapchain, XrSwapchain_T*> g_swapchains;
#if XR_PTR_SIZE != 8
std::uint64_t g_nextSwapchainHandle = 1;
#endif

std::mutex g_spaceMutex;
std::unordered_map<XrSpace, XrSpace_T*> g_spaces;
#if XR_PTR_SIZE != 8
std::uint64_t g_nextSpaceHandle = 1;
#endif

std::mutex g_handTrackerMutex;
std::unordered_map<XrHandTrackerEXT, XrHandTracker_T*> g_handTrackers;
#if XR_PTR_SIZE != 8
std::uint64_t g_nextHandTrackerHandle = 1;
#endif

std::mutex g_actionMutex;
std::unordered_map<XrActionSet, XrActionSet_T*> g_actionSets;
std::unordered_map<XrAction, XrAction_T*> g_actions;

std::mutex g_headPoseMutex;
XrQuaternionf g_headOrientation{0.0f, 0.0f, 0.0f, 1.0f};
bool g_hasHeadOrientation = false;
std::chrono::steady_clock::time_point g_headOrientationUpdatedAt{};
constexpr auto kHeadOrientationStaleTimeout = std::chrono::milliseconds(500);

bool readFreshHeadOrientation(XrQuaternionf* orientation) {
    if (orientation == nullptr) return false;
    std::lock_guard<std::mutex> lock(g_headPoseMutex);
    if (!g_hasHeadOrientation ||
        g_headOrientationUpdatedAt.time_since_epoch().count() == 0 ||
        std::chrono::steady_clock::now() - g_headOrientationUpdatedAt >
            kHeadOrientationStaleTimeout) {
        *orientation = {0.0f, 0.0f, 0.0f, 1.0f};
        return false;
    }
    *orientation = g_headOrientation;
    return true;
}

struct EndXrControllerInput {
    bool active = false;
    float trigger = 0.0f, grip = 0.0f, stickX = 0.0f, stickY = 0.0f;
    std::uint32_t buttons = 0;
    bool poseActive = false;
    XrPosef gripPose{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
    bool handJointsActive = false;
    std::array<float, XR_HAND_JOINT_COUNT_EXT * 8> handJoints{};
    std::chrono::steady_clock::time_point updatedAt{};
};
std::mutex g_controllerInputMutex;
std::array<EndXrControllerInput, 2> g_controllerInputs{};
thread_local std::uint32_t g_lastGlesError = 0;

XrInstance makeInstanceHandle(XrInstance_T* object) {
#if XR_PTR_SIZE == 8
    return reinterpret_cast<XrInstance>(object);
#else
    (void)object;
    return g_nextInstanceHandle++;
#endif
}
XrSession makeSessionHandle(XrSession_T* object) {
#if XR_PTR_SIZE == 8
    return reinterpret_cast<XrSession>(object);
#else
    (void)object;
    return g_nextSessionHandle++;
#endif
}
XrSwapchain makeSwapchainHandle(XrSwapchain_T* object) {
#if XR_PTR_SIZE == 8
    return reinterpret_cast<XrSwapchain>(object);
#else
    (void)object;
    return g_nextSwapchainHandle++;
#endif
}
XrSpace makeSpaceHandle(XrSpace_T* object) {
#if XR_PTR_SIZE == 8
    return reinterpret_cast<XrSpace>(object);
#else
    (void)object;
    return g_nextSpaceHandle++;
#endif
}

XrHandTrackerEXT makeHandTrackerHandle(XrHandTracker_T* object) {
#if XR_PTR_SIZE == 8
    return reinterpret_cast<XrHandTrackerEXT>(object);
#else
    (void)object;
    return g_nextHandTrackerHandle++;
#endif
}
XrActionSet makeActionSetHandle(XrActionSet_T* object) {
    return reinterpret_cast<XrActionSet>(object);
}
XrAction makeActionHandle(XrAction_T* object) {
    return reinterpret_cast<XrAction>(object);
}

struct BaseInStructure {
    XrStructureType type;
    const void* next;
};

constexpr const char* kAndroidExtension =
    ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME;
constexpr const char* kGlesExtension =
    ENDXR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME;

bool isValidInstance(XrInstance instance) {
    if (instance == XR_NULL_HANDLE) return false;
    std::lock_guard<std::mutex> lock(g_instanceMutex);
    return g_instances.find(instance) != g_instances.end();
}
XrInstance_T* getInstance(XrInstance instance) {
    std::lock_guard<std::mutex> lock(g_instanceMutex);
    const auto found = g_instances.find(instance);
    return found == g_instances.end() ? nullptr : found->second;
}
bool isLocalFloorEnabled(const XrSession_T* session) {
    if (session == nullptr) return false;
    const XrInstance_T* instance = getInstance(session->owner);
    if (instance == nullptr) return false;
    return instance->requestedApiVersion >= XR_MAKE_VERSION(1, 1, 0) ||
        instance->localFloorExtensionEnabled;
}
XrSession_T* getSession(XrSession session) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    const auto found = g_sessions.find(session);
    return found == g_sessions.end() ? nullptr : found->second;
}
XrSwapchain_T* getSwapchain(XrSwapchain swapchain) {
    std::lock_guard<std::mutex> lock(g_swapchainMutex);
    const auto found = g_swapchains.find(swapchain);
    return found == g_swapchains.end() ? nullptr : found->second;
}
XrSpace_T* getSpace(XrSpace space) {
    std::lock_guard<std::mutex> lock(g_spaceMutex);
    const auto found = g_spaces.find(space);
    return found == g_spaces.end() ? nullptr : found->second;
}

XrHandTracker_T* getHandTracker(XrHandTrackerEXT tracker) {
    std::lock_guard<std::mutex> lock(g_handTrackerMutex);
    const auto found = g_handTrackers.find(tracker);
    return found == g_handTrackers.end() ? nullptr : found->second;
}
XrActionSet_T* getActionSet(XrActionSet actionSet) {
    std::lock_guard<std::mutex> lock(g_actionMutex);
    const auto found = g_actionSets.find(actionSet);
    return found == g_actionSets.end() ? nullptr : found->second;
}
XrAction_T* getAction(XrAction action) {
    std::lock_guard<std::mutex> lock(g_actionMutex);
    const auto found = g_actions.find(action);
    return found == g_actions.end() ? nullptr : found->second;
}
const XrInstanceCreateInfoAndroidKHR* findAndroidCreateInfo(const void* next) {
    auto* current = static_cast<const BaseInStructure*>(next);
    while (current != nullptr) {
        if (current->type == XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR) {
            return reinterpret_cast<const XrInstanceCreateInfoAndroidKHR*>(current);
        }
        current = static_cast<const BaseInStructure*>(current->next);
    }
    return nullptr;
}
const XrGraphicsBindingOpenGLESAndroidKHR* findGlesBinding(const void* next) {
    auto* current = static_cast<const BaseInStructure*>(next);
    while (current != nullptr) {
        if (current->type == XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR) {
            return reinterpret_cast<const XrGraphicsBindingOpenGLESAndroidKHR*>(current);
        }
        current = static_cast<const BaseInStructure*>(current->next);
    }
    return nullptr;
}
XrTime monotonicTimeNow() {
    return static_cast<XrTime>(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}
template <typename EventT>
void queueInstanceEvent(XrInstance_T* instance, const EventT& event) {
    if (instance == nullptr) return;
    static_assert(sizeof(EventT) <= sizeof(XrEventDataBuffer), "Event exceeds XrEventDataBuffer");
    XrEventDataBuffer buffer{};
    std::memcpy(&buffer, &event, sizeof(event));
    std::lock_guard<std::mutex> lock(instance->eventMutex);
    instance->pendingSessionEvents.push_back(buffer);
}

void queueSessionState(XrSession_T* session, XrSessionState state) {
    session->state = state;
    XrInstance_T* instance = getInstance(session->owner);
    if (instance == nullptr) return;
    XrEventDataSessionStateChanged event{};
    event.type = XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED;
    event.session = reinterpret_cast<XrSession>(session);
    event.state = state;
    event.time = monotonicTimeNow();
#if XR_PTR_SIZE != 8
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        for (const auto& entry : g_sessions) {
            if (entry.second == session) {
                event.session = entry.first;
                break;
            }
        }
    }
#endif
    queueInstanceEvent(instance, event);
}
bool graphicsContextIsCurrent(const XrSession_T* session) {
#if defined(__ANDROID__)
    return session != nullptr &&
        eglGetCurrentDisplay() == session->display &&
        eglGetCurrentContext() == session->context;
#else
    return session != nullptr && session->display != nullptr &&
        session->config != nullptr && session->context != nullptr;
#endif
}
bool createGlesTexture(
    std::uint32_t width,
    std::uint32_t height,
    std::int64_t format,
    std::uint32_t arraySize,
    GLuint* output
) {
    if (output == nullptr || width == 0 || height == 0 || arraySize == 0) return false;
#if defined(__ANDROID__)
    g_lastGlesError = 0;
    auto captureError = []() {
        const GLenum error = glGetError();
        if (error != GL_NO_ERROR && g_lastGlesError == 0) g_lastGlesError = error;
        return error;
    };
    const GLenum target = arraySize > 1 ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_2D;
    const GLenum filter = isDepthSwapchainFormat(format) ? GL_NEAREST : GL_LINEAR;
    const GLenum internalFormat = static_cast<GLenum>(format);
    GLuint texture = 0;
    glGenTextures(1, &texture);
    if (captureError() != GL_NO_ERROR || texture == 0) return false;
    glBindTexture(target, texture);
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, static_cast<GLint>(filter));
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    glTexParameteri(target, GL_TEXTURE_MAG_FILTER, static_cast<GLint>(filter));
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    if (arraySize > 1) {
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, internalFormat, static_cast<GLsizei>(width),
                       static_cast<GLsizei>(height), static_cast<GLsizei>(arraySize));
    } else {
        glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, static_cast<GLsizei>(width),
                       static_cast<GLsizei>(height));
    }
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    *output = texture;
#else
    (void)format;
    (void)arraySize;
    static std::uint32_t nextTexture = 1;
    *output = nextTexture++;
#endif
    return true;
}
void destroyGlesTexture(GLuint texture) {
    if (texture == 0) return;
#if defined(__ANDROID__)
    glDeleteTextures(1, &texture);
#else
    (void)texture;
#endif
}
void destroySessionFramebuffer(XrSession_T* session) {
    if (session == nullptr || session->compositorFramebuffer == 0) return;
#if defined(__ANDROID__)
    glDeleteFramebuffers(1, &session->compositorFramebuffer);
#endif
    session->compositorFramebuffer = 0;
}
bool compositeProjectionLayer(XrSession_T* session,
                              const XrCompositionLayerProjection* projection,
                              XrResult* error) {
    if (projection == nullptr || projection->viewCount != 2 ||
        projection->views == nullptr) {
        if (error != nullptr) *error = XR_ERROR_LAYER_INVALID;
        return false;
    }
    std::array<XrSwapchain_T*, 2> swapchains{};
    std::array<std::uint32_t, 2> imageIndices{};
    for (std::uint32_t eye = 0; eye < 2; ++eye) {
        const auto& view = projection->views[eye];
        if (view.type != XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW ||
            view.subImage.swapchain == XR_NULL_HANDLE ||
            view.subImage.imageRect.offset.x < 0 ||
            view.subImage.imageRect.offset.y < 0 ||
            view.subImage.imageRect.extent.width <= 0 ||
            view.subImage.imageRect.extent.height <= 0) {
            if (error != nullptr) *error = XR_ERROR_LAYER_INVALID;
            return false;
        }
        XrSwapchain_T* swapchain = getSwapchain(view.subImage.swapchain);
        if (swapchain == nullptr || swapchain->isDepth ||
            view.subImage.imageArrayIndex >= swapchain->arraySize ||
            swapchain->owner != reinterpret_cast<XrSession>(session) ||
            swapchain->lastReleasedIndex >= swapchain->imageCount) {
#if XR_PTR_SIZE != 8
            XrSession ownerHandle = XR_NULL_HANDLE;
            {
                std::lock_guard<std::mutex> lock(g_sessionMutex);
                for (const auto& entry : g_sessions) {
                    if (entry.second == session) ownerHandle = entry.first;
                }
            }
            if (swapchain == nullptr || swapchain->isDepth ||
                view.subImage.imageArrayIndex >= swapchain->arraySize ||
                swapchain->owner != ownerHandle ||
                swapchain->lastReleasedIndex >= swapchain->imageCount) {
                if (error != nullptr) *error = XR_ERROR_LAYER_INVALID;
                return false;
            }
#else
            if (error != nullptr) *error = XR_ERROR_LAYER_INVALID;
            return false;
#endif
        }
        const auto& rect = view.subImage.imageRect;
        if (static_cast<std::uint32_t>(rect.offset.x) + 
                static_cast<std::uint32_t>(rect.extent.width) > swapchain->width ||
            static_cast<std::uint32_t>(rect.offset.y) +
                static_cast<std::uint32_t>(rect.extent.height) > swapchain->height) {
            if (error != nullptr) *error = XR_ERROR_SWAPCHAIN_RECT_INVALID;
            return false;
        }
        if (swapchain->acquiredIndex != std::numeric_limits<std::uint32_t>::max()) {
            if (error != nullptr) *error = XR_ERROR_CALL_ORDER_INVALID;
            return false;
        }
        swapchains[eye] = swapchain;
        imageIndices[eye] = swapchain->lastReleasedIndex;
    }

#if defined(__ANDROID__)
    if (!graphicsContextIsCurrent(session)) {
        if (error != nullptr) *error = XR_ERROR_GRAPHICS_DEVICE_INVALID;
        return false;
    }
    EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    EGLint screenWidth = 0;
    EGLint screenHeight = 0;
    if (surface == EGL_NO_SURFACE ||
        eglQuerySurface(session->display, surface, EGL_WIDTH, &screenWidth) != EGL_TRUE ||
        eglQuerySurface(session->display, surface, EGL_HEIGHT, &screenHeight) != EGL_TRUE ||
        screenWidth < 2 || screenHeight < 1) {
        if (error != nullptr) *error = XR_ERROR_GRAPHICS_DEVICE_INVALID;
        return false;
    }

    GLint previousReadFramebuffer = 0;
    GLint previousDrawFramebuffer = 0;
    GLint previousScissorEnabled = glIsEnabled(GL_SCISSOR_TEST);
    GLboolean previousColorMask[4]{};
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previousReadFramebuffer);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previousDrawFramebuffer);
    glGetBooleanv(GL_COLOR_WRITEMASK, previousColorMask);

    if (session->compositorFramebuffer == 0) {
        glGenFramebuffers(1, &session->compositorFramebuffer);
    }
    if (session->compositorFramebuffer == 0) {
        if (error != nullptr) *error = XR_ERROR_RUNTIME_FAILURE;
        return false;
    }

    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, session->compositorFramebuffer);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);

    bool success = true;
    const GLint halfWidth = screenWidth / 2;
    for (std::uint32_t eye = 0; eye < 2; ++eye) {
        const auto& subImage = projection->views[eye].subImage;
        const auto& rect = subImage.imageRect;
        if (swapchains[eye]->arraySize > 1) {
            glFramebufferTextureLayer(
                GL_READ_FRAMEBUFFER,
                GL_COLOR_ATTACHMENT0,
                swapchains[eye]->images[imageIndices[eye]],
                0,
                static_cast<GLint>(subImage.imageArrayIndex)
            );
        } else {
            glFramebufferTexture2D(
                GL_READ_FRAMEBUFFER,
                GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D,
                swapchains[eye]->images[imageIndices[eye]],
                0
            );
        }
        if (!swapchains[eye]->fboValidated) {
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                success = false;
                break;
            }
        }
        const GLint destinationLeft = eye == 0 ? 0 : halfWidth;
        const GLint destinationRight = eye == 0 ? halfWidth : screenWidth;
        glBlitFramebuffer(
            rect.offset.x,
            rect.offset.y,
            rect.offset.x + rect.extent.width,
            rect.offset.y + rect.extent.height,
            destinationLeft,
            0,
            destinationRight,
            screenHeight,
            GL_COLOR_BUFFER_BIT,
            GL_LINEAR
        );
        if (!swapchains[eye]->fboValidated && glGetError() != GL_NO_ERROR) {
            success = false;
            break;
        }
    }
    if (success) {
        swapchains[0]->fboValidated = true;
        swapchains[1]->fboValidated = true;
    }

    glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(previousReadFramebuffer));
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(previousDrawFramebuffer));
    glColorMask(previousColorMask[0], previousColorMask[1],
                previousColorMask[2], previousColorMask[3]);
    if (previousScissorEnabled == GL_TRUE) glEnable(GL_SCISSOR_TEST);
    else glDisable(GL_SCISSOR_TEST);

    if (!success) {
        if (error != nullptr) *error = XR_ERROR_RUNTIME_FAILURE;
        return false;
    }
#else
    (void)swapchains;
    (void)imageIndices;
#endif
    if (error != nullptr) *error = XR_SUCCESS;
    return true;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetInstanceProcAddr(
    XrInstance instance, const char* name, PFN_xrVoidFunction* function
);

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateApiLayerProperties(
    std::uint32_t capacity, std::uint32_t* count, XrApiLayerProperties*
) {
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 0;
    (void)capacity;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateInstanceExtensionProperties(
    const char* layerName, std::uint32_t capacity,
    std::uint32_t* count, XrExtensionProperties* properties
) {
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (layerName != nullptr && layerName[0] != '\0') {
        return XR_ERROR_API_LAYER_NOT_PRESENT;
    }
    constexpr std::array<const char*, 12> names{{
        kAndroidExtension,
        kGlesExtension,
        XR_EXT_HAND_TRACKING_EXTENSION_NAME,
        XR_EXT_HAND_INTERACTION_EXTENSION_NAME,
        ENDXR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME,
        XR_KHR_COMPOSITION_LAYER_DEPTH_EXTENSION_NAME,
        ENDXR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME,
        XR_KHR_LOCATE_SPACES_EXTENSION_NAME,
        XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME,
        XR_EXT_LOCAL_FLOOR_EXTENSION_NAME,
        XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME,
        XR_FB_COLOR_SPACE_EXTENSION_NAME
    }};
    constexpr std::array<std::uint32_t, 12> versions{{
        ENDXR_KHR_ANDROID_CREATE_INSTANCE_SPEC_VERSION,
        ENDXR_KHR_OPENGL_ES_ENABLE_SPEC_VERSION,
        XR_EXT_hand_tracking_SPEC_VERSION,
        XR_EXT_hand_interaction_SPEC_VERSION,
        ENDXR_KHR_ANDROID_THREAD_SETTINGS_SPEC_VERSION,
        XR_KHR_composition_layer_depth_SPEC_VERSION,
        ENDXR_KHR_CONVERT_TIMESPEC_TIME_SPEC_VERSION,
        XR_KHR_locate_spaces_SPEC_VERSION,
        XR_EXT_performance_settings_SPEC_VERSION,
        XR_EXT_local_floor_SPEC_VERSION,
        XR_FB_display_refresh_rate_SPEC_VERSION,
        XR_FB_color_space_SPEC_VERSION
    }};
    *count = static_cast<std::uint32_t>(names.size());
    if (capacity == 0) return XR_SUCCESS;
    if (properties == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const std::uint32_t fillCount = std::min(capacity, *count);
    for (std::uint32_t i = 0; i < fillCount; ++i) {
        if (properties[i].type != XR_TYPE_EXTENSION_PROPERTIES) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
        std::snprintf(properties[i].extensionName,
                      sizeof(properties[i].extensionName), "%s", names[i]);
        properties[i].extensionVersion = versions[i];
    }
    return capacity < *count ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateInstanceImpl(
    const XrInstanceCreateInfo* createInfo, XrInstance* instance
) {
    if (instance == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *instance = XR_NULL_HANDLE;
    if (createInfo == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (createInfo->type != XR_TYPE_INSTANCE_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (createInfo->enabledApiLayerCount != 0) return XR_ERROR_API_LAYER_NOT_PRESENT;
    if (createInfo->enabledExtensionCount > 0 && createInfo->enabledExtensionNames == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }

    bool androidEnabled = false;
    bool glesEnabled = false;
    bool handTrackingEnabled = false;
    bool handInteractionEnabled = false;
    bool androidThreadSettingsEnabled = false;
    bool compositionLayerDepthEnabled = false;
    bool convertTimespecTimeEnabled = false;
    bool locateSpacesEnabled = false;
    bool performanceSettingsEnabled = false;
    bool localFloorEnabled = false;
    bool displayRefreshRateEnabled = false;
    bool colorSpaceEnabled = false;
    for (std::uint32_t i = 0; i < createInfo->enabledExtensionCount; ++i) {
        const char* name = createInfo->enabledExtensionNames[i];
        if (name == nullptr) return XR_ERROR_VALIDATION_FAILURE;
        if (std::strcmp(name, kAndroidExtension) == 0) {
            if (androidEnabled) return XR_ERROR_NAME_DUPLICATED;
            androidEnabled = true;
        } else if (std::strcmp(name, kGlesExtension) == 0) {
            if (glesEnabled) return XR_ERROR_NAME_DUPLICATED;
            glesEnabled = true;
        } else if (std::strcmp(name, XR_EXT_HAND_TRACKING_EXTENSION_NAME) == 0) {
            if (handTrackingEnabled) return XR_ERROR_NAME_DUPLICATED;
            handTrackingEnabled = true;
        } else if (std::strcmp(name, XR_EXT_HAND_INTERACTION_EXTENSION_NAME) == 0) {
            if (handInteractionEnabled) return XR_ERROR_NAME_DUPLICATED;
            handInteractionEnabled = true;
        } else if (std::strcmp(name, ENDXR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME) == 0) {
            if (androidThreadSettingsEnabled) return XR_ERROR_NAME_DUPLICATED;
            androidThreadSettingsEnabled = true;
        } else if (std::strcmp(name, XR_KHR_COMPOSITION_LAYER_DEPTH_EXTENSION_NAME) == 0) {
            if (compositionLayerDepthEnabled) return XR_ERROR_NAME_DUPLICATED;
            compositionLayerDepthEnabled = true;
        } else if (std::strcmp(name, ENDXR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME) == 0) {
            if (convertTimespecTimeEnabled) return XR_ERROR_NAME_DUPLICATED;
            convertTimespecTimeEnabled = true;
        } else if (std::strcmp(name, XR_KHR_LOCATE_SPACES_EXTENSION_NAME) == 0) {
            if (locateSpacesEnabled) return XR_ERROR_NAME_DUPLICATED;
            locateSpacesEnabled = true;
        } else if (std::strcmp(name, XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME) == 0) {
            if (performanceSettingsEnabled) return XR_ERROR_NAME_DUPLICATED;
            performanceSettingsEnabled = true;
        } else if (std::strcmp(name, XR_EXT_LOCAL_FLOOR_EXTENSION_NAME) == 0) {
            if (localFloorEnabled) return XR_ERROR_NAME_DUPLICATED;
            localFloorEnabled = true;
        } else if (std::strcmp(name, XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME) == 0) {
            if (displayRefreshRateEnabled) return XR_ERROR_NAME_DUPLICATED;
            displayRefreshRateEnabled = true;
        } else if (std::strcmp(name, XR_FB_COLOR_SPACE_EXTENSION_NAME) == 0) {
            if (colorSpaceEnabled) return XR_ERROR_NAME_DUPLICATED;
            colorSpaceEnabled = true;
        } else {
            return XR_ERROR_EXTENSION_NOT_PRESENT;
        }
    }

    const XrInstanceCreateInfoAndroidKHR* androidInfo = nullptr;
    if (androidEnabled) {
        androidInfo = findAndroidCreateInfo(createInfo->next);
        if (androidInfo == nullptr ||
            androidInfo->applicationVM == nullptr ||
            androidInfo->applicationActivity == nullptr) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
    } else if (findAndroidCreateInfo(createInfo->next) != nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }

    const XrVersion requested = createInfo->applicationInfo.apiVersion;
    if (!isSupportedApiVersion(requested)) {
        return XR_ERROR_API_VERSION_UNSUPPORTED;
    }

    XrInstance_T* created = new (std::nothrow) XrInstance_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->requestedApiVersion = requested;
    created->telemetryId = g_nextTelemetryIdentity.fetch_add(1, std::memory_order_relaxed);
    created->androidExtensionEnabled = androidEnabled;
    created->glesExtensionEnabled = glesEnabled;
    created->handTrackingExtensionEnabled = handTrackingEnabled;
    created->handInteractionExtensionEnabled = handInteractionEnabled;
    created->androidThreadSettingsExtensionEnabled = androidThreadSettingsEnabled;
    created->compositionLayerDepthExtensionEnabled = compositionLayerDepthEnabled;
    created->convertTimespecTimeExtensionEnabled = convertTimespecTimeEnabled;
    created->locateSpacesExtensionEnabled = locateSpacesEnabled;
    created->performanceSettingsExtensionEnabled = performanceSettingsEnabled;
    created->localFloorExtensionEnabled = localFloorEnabled;
    created->displayRefreshRateExtensionEnabled = displayRefreshRateEnabled;
    created->colorSpaceExtensionEnabled = colorSpaceEnabled;
    if (androidInfo != nullptr) {
#if defined(__ANDROID__)
        created->applicationVm = reinterpret_cast<JavaVM*>(androidInfo->applicationVM);
        JNIEnv* env = nullptr;
        bool attachedHere = false;
        if (created->applicationVm == nullptr) {
            delete created;
            return XR_ERROR_INITIALIZATION_FAILED;
        }
        jint envStatus = created->applicationVm->GetEnv(
            reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (envStatus == JNI_EDETACHED) {
            envStatus = created->applicationVm->AttachCurrentThread(&env, nullptr);
            attachedHere = envStatus == JNI_OK;
        }
        if (envStatus != JNI_OK || env == nullptr) {
            if (attachedHere) created->applicationVm->DetachCurrentThread();
            delete created;
            return XR_ERROR_INITIALIZATION_FAILED;
        }
        created->applicationActivity = env->NewGlobalRef(
            reinterpret_cast<jobject>(androidInfo->applicationActivity));
        const bool referenceFailed =
            created->applicationActivity == nullptr || env->ExceptionCheck();
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attachedHere) created->applicationVm->DetachCurrentThread();
        if (referenceFailed) {
            delete created;
            return XR_ERROR_INITIALIZATION_FAILED;
        }
#else
        created->applicationVm = androidInfo->applicationVM;
        created->applicationActivity = androidInfo->applicationActivity;
#endif
    }
    const XrInstance createdHandle = makeInstanceHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_instanceMutex);
        g_instances.emplace(createdHandle, created);
    }
#if defined(__ANDROID__)
    if (created->androidExtensionEnabled) {
        enqueueRuntimeTelemetry(created, "xr_create_instance");
    }
    if (created->androidExtensionEnabled &&
        !startExternalControllerInputPolling(created)) {
        // Controller IPC is optional to the OpenXR instance itself. Do not make
        // a camera/provider/thread failure prevent the game from creating its
        // runtime instance and attempting graphics/session setup.
        endxrClearControllerInput();
        endxrClearHeadOrientation();
        endxrClearHandPoses();
        __android_log_print(
            ANDROID_LOG_WARN,
            "EndXR",
            "Input IPC worker unavailable; continuing without live external input."
        );
        // Telemetry is best-effort too; attempt the creation event directly if
        // the shared polling/telemetry worker could not be started.
        flushRuntimeTelemetryImpl(created);
    }
#endif
    *instance = createdHandle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroyInstanceImpl(XrInstance instance) {
#if defined(__ANDROID__)
    const auto traceStartedAt = std::chrono::steady_clock::now();
#endif
    XrInstance_T* object = nullptr;
    {
        std::lock_guard<std::mutex> actionLock(g_actionMutex);
        for (const auto& entry : g_actionSets) {
            if (entry.second->owner == instance) return XR_ERROR_CALL_ORDER_INVALID;
        }
    }
    {
        std::lock_guard<std::mutex> sessionLock(g_sessionMutex);
        for (const auto& entry : g_sessions) {
            if (entry.second->owner == instance) return XR_ERROR_CALL_ORDER_INVALID;
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_instanceMutex);
        const auto found = g_instances.find(instance);
        if (found == g_instances.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        g_instances.erase(found);
    }
#if defined(__ANDROID__)
    const auto traceDuration = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - traceStartedAt).count();
    enqueueRuntimeTrace(
        object, 0, "INSTANCE", "xrDestroyInstance", XR_SUCCESS,
        static_cast<std::uint64_t>(std::max<std::int64_t>(0, traceDuration)),
        0, "instance_destroyed=true"
    );
    enqueueRuntimeTelemetry(object, "xr_destroy_instance");
    stopExternalControllerInputPolling(object);
    if (object->applicationVm != nullptr && object->applicationActivity != nullptr) {
        JNIEnv* env = nullptr;
        bool attachedHere = false;
        const jint envStatus = object->applicationVm->GetEnv(
            reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (envStatus == JNI_EDETACHED) {
            if (object->applicationVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attachedHere = true;
            }
        }
        if (env != nullptr) {
            env->DeleteGlobalRef(object->applicationActivity);
        }
        if (attachedHere) object->applicationVm->DetachCurrentThread();
    }
#endif
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetInstanceProperties(
    XrInstance instance, XrInstanceProperties* properties
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (properties == nullptr || properties->type != XR_TYPE_INSTANCE_PROPERTIES) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    properties->runtimeVersion = kEndXrApiVersion;
    std::snprintf(properties->runtimeName, sizeof(properties->runtimeName),
                  "%s", kRuntimeName);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetSystemImpl(
    XrInstance instance, const XrSystemGetInfo* getInfo, XrSystemId* systemId
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (getInfo == nullptr || systemId == nullptr ||
        getInfo->type != XR_TYPE_SYSTEM_GET_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (getInfo->formFactor != XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY) {
        return XR_ERROR_FORM_FACTOR_UNSUPPORTED;
    }
    *systemId = kEndXrSystemId;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetSystemProperties(
    XrInstance instance, XrSystemId systemId, XrSystemProperties* properties
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (properties == nullptr || properties->type != XR_TYPE_SYSTEM_PROPERTIES) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    properties->systemId = kEndXrSystemId;
    properties->vendorId = kEndXrVendorId;
    std::snprintf(properties->systemName, sizeof(properties->systemName), "%s", kSystemName);
    properties->graphicsProperties.maxSwapchainImageWidth = kMaximumEyeWidth;
    properties->graphicsProperties.maxSwapchainImageHeight = kMaximumEyeHeight;
    // The current phone compositor accepts one projection layer. Report the
    // implemented limit rather than claiming the OpenXR minimum of 16 while
    // xrEndFrame would reject multiple layers.
    properties->graphicsProperties.maxLayerCount = kMaxSupportedLayers;
    properties->trackingProperties.orientationTracking = XR_TRUE;
    properties->trackingProperties.positionTracking = XR_FALSE;
    auto* next = static_cast<BaseInStructure*>(properties->next);
    while (next != nullptr) {
        if (next->type == XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT) {
            auto* handProperties =
                reinterpret_cast<XrSystemHandTrackingPropertiesEXT*>(next);
            handProperties->supportsHandTracking =
                getInstance(instance)->handTrackingExtensionEnabled ? XR_TRUE : XR_FALSE;
        } else if (next->type == XR_TYPE_SYSTEM_COLOR_SPACE_PROPERTIES_FB) {
            auto* colorSpaceProps =
                reinterpret_cast<XrSystemColorSpacePropertiesFB*>(next);
            colorSpaceProps->colorSpace = XR_COLOR_SPACE_REC709_FB;
        }
        next = const_cast<BaseInStructure*>(
            static_cast<const BaseInStructure*>(next->next));
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateViewConfigurations(
    XrInstance instance, XrSystemId systemId, std::uint32_t capacity,
    std::uint32_t* count, XrViewConfigurationType* configurations
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 1;
    if (capacity == 0) return XR_SUCCESS;
    if (configurations == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    configurations[0] = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetViewConfigurationProperties(
    XrInstance instance, XrSystemId systemId,
    XrViewConfigurationType viewType, XrViewConfigurationProperties* properties
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (viewType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    if (properties == nullptr ||
        properties->type != XR_TYPE_VIEW_CONFIGURATION_PROPERTIES) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    properties->viewConfigurationType = viewType;
    properties->fovMutable = XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateViewConfigurationViews(
    XrInstance instance, XrSystemId systemId, XrViewConfigurationType viewType,
    std::uint32_t capacity, std::uint32_t* count, XrViewConfigurationView* views
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (viewType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 2;
    if (capacity == 0) return XR_SUCCESS;
    if (views == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < 2) return XR_ERROR_SIZE_INSUFFICIENT;
    for (std::uint32_t i = 0; i < 2; ++i) {
        if (views[i].type != XR_TYPE_VIEW_CONFIGURATION_VIEW) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
        views[i].recommendedImageRectWidth = kRecommendedEyeWidth;
        views[i].maxImageRectWidth = kMaximumEyeWidth;
        views[i].recommendedImageRectHeight = kRecommendedEyeHeight;
        views[i].maxImageRectHeight = kMaximumEyeHeight;
        views[i].recommendedSwapchainSampleCount = 1;
        views[i].maxSwapchainSampleCount = 1;
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateEnvironmentBlendModes(
    XrInstance instance, XrSystemId systemId,
    XrViewConfigurationType viewType, std::uint32_t capacity,
    std::uint32_t* count, XrEnvironmentBlendMode* modes
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (viewType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 1;
    if (capacity == 0) return XR_SUCCESS;
    if (modes == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    modes[0] = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetOpenGLESGraphicsRequirementsKHRImpl(
    XrInstance instance, XrSystemId systemId,
    XrGraphicsRequirementsOpenGLESKHR* requirements
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (!owner->glesExtensionEnabled) return XR_ERROR_EXTENSION_NOT_PRESENT;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (requirements == nullptr ||
        requirements->type != XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    requirements->minApiVersionSupported = XR_MAKE_VERSION(3, 0, 0);
    requirements->maxApiVersionSupported = XR_MAKE_VERSION(3, 2, 0);
    owner->glesRequirementsQueried = true;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateSessionImpl(
    XrInstance instance, const XrSessionCreateInfo* createInfo, XrSession* session
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || session == nullptr ||
        createInfo->type != XR_TYPE_SESSION_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *session = XR_NULL_HANDLE;
    if (createInfo->systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (!owner->glesExtensionEnabled) return XR_ERROR_EXTENSION_NOT_PRESENT;
    if (!owner->glesRequirementsQueried) return XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING;
    const auto* binding = findGlesBinding(createInfo->next);
    if (binding == nullptr ||
        binding->type != XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR ||
        binding->display == nullptr || binding->config == nullptr ||
        binding->context == nullptr) {
        return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    }
#if defined(__ANDROID__)
    if (eglGetCurrentDisplay() != reinterpret_cast<EGLDisplay>(binding->display) ||
        eglGetCurrentContext() != reinterpret_cast<EGLContext>(binding->context)) {
        return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    }
#endif
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        for (const auto& entry : g_sessions) {
            if (entry.second->owner == instance) return XR_ERROR_LIMIT_REACHED;
        }
    }
    XrSession_T* created = new (std::nothrow) XrSession_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = instance;
    created->telemetryId = g_nextTelemetryIdentity.fetch_add(1, std::memory_order_relaxed);
    created->systemId = createInfo->systemId;
    created->display = reinterpret_cast<EGLDisplay>(binding->display);
    created->config = reinterpret_cast<EGLConfig>(binding->config);
    created->context = reinterpret_cast<EGLContext>(binding->context);
    const XrSession handle = makeSessionHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_sessions.emplace(handle, created);
    }
    queueSessionState(created, XR_SESSION_STATE_READY);
#if defined(__ANDROID__)
    enqueueRuntimeTelemetry(owner, "xr_create_session", created->telemetryId);
#endif
    *session = handle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroySessionImpl(XrSession session) {
    XrSession_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        const auto found = g_sessions.find(session);
        if (found == g_sessions.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        if (object->running) return XR_ERROR_SESSION_RUNNING;
        if (!object->swapchains.empty() || !object->spaces.empty() ||
            !object->handTrackers.empty()) {
            return XR_ERROR_CALL_ORDER_INVALID;
        }
        g_sessions.erase(found);
    }
#if defined(__ANDROID__)
    if (XrInstance_T* owner = getInstance(object->owner)) {
        enqueueRuntimeTelemetry(owner, "xr_destroy_session", object->telemetryId);
    }
#endif
    {
        std::lock_guard<std::mutex> actionLock(g_actionMutex);
        for (XrActionSet actionSet : object->attachedActionSets) {
            const auto found = g_actionSets.find(actionSet);
            if (found != g_actionSets.end() && found->second->attachedSession == session) {
                found->second->attachedSession = XR_NULL_HANDLE;
            }
        }
    }
    destroySessionFramebuffer(object);
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrBeginSessionImpl(
    XrSession session, const XrSessionBeginInfo* beginInfo
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (beginInfo == nullptr || beginInfo->type != XR_TYPE_SESSION_BEGIN_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (object->state != XR_SESSION_STATE_READY) return XR_ERROR_SESSION_NOT_READY;
    if (beginInfo->primaryViewConfigurationType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    object->running = true;
    object->exitRequested = false;
    object->frameWaited = false;
    object->frameBegun = false;
    object->hasPredictedTime = false;
    queueSessionState(object, XR_SESSION_STATE_SYNCHRONIZED);
    queueSessionState(object, XR_SESSION_STATE_VISIBLE);
    queueSessionState(object, XR_SESSION_STATE_FOCUSED);
#if defined(__ANDROID__)
    if (XrInstance_T* owner = getInstance(object->owner)) {
        enqueueRuntimeTelemetry(owner, "xr_begin_session", object->telemetryId);
    }
#endif
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrRequestExitSession(XrSession session) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (!object->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (object->exitRequested) return XR_ERROR_CALL_ORDER_INVALID;
    object->exitRequested = true;
    queueSessionState(object, XR_SESSION_STATE_STOPPING);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEndSessionImpl(XrSession session) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (!object->running || !object->exitRequested ||
        object->state != XR_SESSION_STATE_STOPPING) {
        return XR_ERROR_SESSION_NOT_STOPPING;
    }
    if (object->frameWaited || object->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    object->running = false;
    object->exitRequested = false;
    queueSessionState(object, XR_SESSION_STATE_IDLE);
#if defined(__ANDROID__)
    if (XrInstance_T* owner = getInstance(object->owner)) {
        enqueueRuntimeTelemetry(owner, "xr_end_session", object->telemetryId);
    }
#endif
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrPollEvent(
    XrInstance instance, XrEventDataBuffer* eventData
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (eventData == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    std::lock_guard<std::mutex> lock(owner->eventMutex);
    if (owner->pendingSessionEvents.empty()) return XR_EVENT_UNAVAILABLE;
    const XrEventDataBuffer event = owner->pendingSessionEvents.front();
    owner->pendingSessionEvents.pop_front();
    std::memset(eventData, 0, sizeof(*eventData));
    std::memcpy(eventData, &event, sizeof(event));
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrWaitFrameImpl(
    XrSession session, const XrFrameWaitInfo* waitInfo, XrFrameState* frameState
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (waitInfo == nullptr || waitInfo->type != XR_TYPE_FRAME_WAIT_INFO ||
        frameState == nullptr || frameState->type != XR_TYPE_FRAME_STATE) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!object->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (object->frameWaited || object->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    const XrTime now = monotonicTimeNow();
    const XrDuration period = sessionFramePeriodNs(object);
    const XrTime next = object->hasPredictedTime
        ? std::max(now + period, object->predictedDisplayTime + 1)
        : now + period;
    object->predictedDisplayTime = next;
    object->predictedDisplayPeriod = period;
    object->hasPredictedTime = true;
    object->frameWaited = true;
    frameState->predictedDisplayTime = next;
    frameState->predictedDisplayPeriod = period;
    frameState->shouldRender =
        object->state == XR_SESSION_STATE_VISIBLE ||
        object->state == XR_SESSION_STATE_FOCUSED ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrBeginFrameImpl(
    XrSession session, const XrFrameBeginInfo* beginInfo
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (beginInfo != nullptr && beginInfo->type != XR_TYPE_FRAME_BEGIN_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!object->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (!object->frameWaited || object->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    object->frameBegun = true;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEndFrameImpl(
    XrSession session, const XrFrameEndInfo* endInfo
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (endInfo == nullptr || endInfo->type != XR_TYPE_FRAME_END_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!object->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (!object->frameWaited || !object->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    if (endInfo->displayTime != object->predictedDisplayTime) return XR_ERROR_TIME_INVALID;
    if (endInfo->environmentBlendMode != XR_ENVIRONMENT_BLEND_MODE_OPAQUE) {
        return XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED;
    }
    if (endInfo->layerCount > kMaxSupportedLayers) {
        return XR_ERROR_LAYER_LIMIT_EXCEEDED;
    }
    if (endInfo->layerCount > 0 && endInfo->layers == nullptr) {
        return XR_ERROR_LAYER_INVALID;
    }
    const XrCompositionLayerProjection* primaryProjection = nullptr;
    for (std::uint32_t i = 0; i < endInfo->layerCount; ++i) {
        const XrCompositionLayerBaseHeader* layer = endInfo->layers[i];
        if (layer == nullptr) {
            return XR_ERROR_LAYER_INVALID;
        }
        if (layer->type == XR_TYPE_COMPOSITION_LAYER_PROJECTION && primaryProjection == nullptr) {
            primaryProjection = reinterpret_cast<const XrCompositionLayerProjection*>(layer);
        }
    }
    if (primaryProjection != nullptr) {
        XrResult composeError = XR_SUCCESS;
        if (!compositeProjectionLayer(object, primaryProjection, &composeError)) {
            object->frameWaited = false;
            object->frameBegun = false;
            return composeError;
        }
    } else if (endInfo->layerCount > 0) {
        // An application submitted only non-projection layers (for example a Quad UI layer)
        // without a stereo projection base. Accept gracefully without failing the frame loop.
    }
    object->frameWaited = false;
    object->frameBegun = false;
#if defined(__ANDROID__)
    if (primaryProjection != nullptr) {
        ++object->telemetryFrameCount;
        if (object->telemetryFrameCount == 1 || object->telemetryFrameCount % 30 == 0) {
            if (XrInstance_T* owner = getInstance(object->owner)) {
                enqueueRuntimeTelemetry(
                    owner,
                    "xr_end_frame",
                    object->telemetryId,
                    object->telemetryFrameCount
                );
            }
        }
    }
#endif
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateReferenceSpaces(
    XrSession session, std::uint32_t capacity, std::uint32_t* count,
    XrReferenceSpaceType* spaces
) {
    const XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const bool includeLocalFloor = isLocalFloorEnabled(object);
    const std::uint32_t total = includeLocalFloor ? 4u : 3u;
    *count = total;
    if (capacity == 0) return XR_SUCCESS;
    if (spaces == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const std::uint32_t fill = std::min(capacity, total);
    if (fill >= 1) spaces[0] = XR_REFERENCE_SPACE_TYPE_VIEW;
    if (fill >= 2) spaces[1] = XR_REFERENCE_SPACE_TYPE_LOCAL;
    if (fill >= 3) spaces[2] = XR_REFERENCE_SPACE_TYPE_STAGE;
    if (includeLocalFloor && fill >= 4) spaces[3] = XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR;
    return capacity < total ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetReferenceSpaceBoundsRect(
    XrSession session,
    XrReferenceSpaceType referenceSpaceType,
    XrExtent2Df* bounds
) {
    const XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (bounds == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    bounds->width = 0.0f;
    bounds->height = 0.0f;
    const bool localFloorAllowed =
        referenceSpaceType == XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR &&
        isLocalFloorEnabled(object);
    if (referenceSpaceType != XR_REFERENCE_SPACE_TYPE_VIEW &&
        referenceSpaceType != XR_REFERENCE_SPACE_TYPE_LOCAL &&
        referenceSpaceType != XR_REFERENCE_SPACE_TYPE_STAGE &&
        !localFloorAllowed) {
        return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    }
    return XR_SPACE_BOUNDS_UNAVAILABLE;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateReferenceSpace(
    XrSession session, const XrReferenceSpaceCreateInfo* createInfo, XrSpace* space
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || space == nullptr ||
        createInfo->type != XR_TYPE_REFERENCE_SPACE_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *space = XR_NULL_HANDLE;
    const bool localFloorAllowed =
        createInfo->referenceSpaceType == XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR &&
        isLocalFloorEnabled(owner);
    if (createInfo->referenceSpaceType != XR_REFERENCE_SPACE_TYPE_VIEW &&
        createInfo->referenceSpaceType != XR_REFERENCE_SPACE_TYPE_LOCAL &&
        createInfo->referenceSpaceType != XR_REFERENCE_SPACE_TYPE_STAGE &&
        !localFloorAllowed) {
        return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    }
    XrSpace_T* created = new (std::nothrow) XrSpace_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = session;
    created->type = createInfo->referenceSpaceType;
    created->poseInReferenceSpace = createInfo->poseInReferenceSpace;
    const XrSpace handle = makeSpaceHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_spaceMutex);
        g_spaces.emplace(handle, created);
    }
    owner->spaces.insert(handle);
    *space = handle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroySpace(XrSpace space) {
    XrSpace_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_spaceMutex);
        const auto found = g_spaces.find(space);
        if (found == g_spaces.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        g_spaces.erase(found);
    }
    XrSession_T* owner = getSession(object->owner);
    if (owner != nullptr) owner->spaces.erase(space);
    if (object->isActionSpace && object->action != XR_NULL_HANDLE) {
        std::lock_guard<std::mutex> actionLock(g_actionMutex);
        const auto action = g_actions.find(object->action);
        if (action != g_actions.end()) action->second->spaces.erase(space);
    }
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrLocateSpace(
    XrSpace space, XrSpace baseSpace, XrTime time, XrSpaceLocation* location
) {
    XrSpace_T* object = getSpace(space);
    XrSpace_T* base = getSpace(baseSpace);
    if (object == nullptr || base == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (object->owner != base->owner) return XR_ERROR_HANDLE_INVALID;
    if (location == nullptr || location->type != XR_TYPE_SPACE_LOCATION) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (time <= 0) return XR_ERROR_TIME_INVALID;

    const XrPosef identity{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
    location->pose = identity;
    location->locationFlags = 0;

    if (object->isActionSpace) {
        if (base->isActionSpace) return XR_SUCCESS;
        XrPosef estimatedPose{};
        if (!locateEstimatedHandPose(object, base, &estimatedPose)) return XR_SUCCESS;
        location->pose = estimatedPose;
        // The palm pose is estimated from camera landmarks and monocular depth.
        // It is usable as a grip interaction pose, but deliberately not TRACKED.
        location->locationFlags =
            XR_SPACE_LOCATION_POSITION_VALID_BIT |
            XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
        return XR_SUCCESS;
    }
    if (base->isActionSpace) return XR_SUCCESS;

    auto isFloorSpace = [](XrReferenceSpaceType t) {
        return t == XR_REFERENCE_SPACE_TYPE_STAGE ||
            t == XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR;
    };
    auto isSupportedRefSpace = [&](XrReferenceSpaceType t) {
        return t == XR_REFERENCE_SPACE_TYPE_LOCAL ||
            t == XR_REFERENCE_SPACE_TYPE_VIEW ||
            isFloorSpace(t);
    };
    if (!isSupportedRefSpace(object->type) || !isSupportedRefSpace(base->type)) {
        return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    }

    const bool involvesView =
        object->type == XR_REFERENCE_SPACE_TYPE_VIEW ||
        base->type == XR_REFERENCE_SPACE_TYPE_VIEW;
    const bool sameReferenceType = object->type == base->type;
    XrPosef viewInLocal = identity;
    if (involvesView && !sameReferenceType &&
        !readFreshHeadOrientation(&viewInLocal.orientation)) {
        return XR_SUCCESS;
    }

    const XrPosef stageInLocal{
        {0.0f, 0.0f, 0.0f, 1.0f},
        {0.0f, -kDefaultStageEyeHeightMeters, 0.0f}
    };
    const auto spaceInLocal = [&](const XrSpace_T* value) {
        const XrPosef frame = value->type == XR_REFERENCE_SPACE_TYPE_VIEW
            ? viewInLocal
            : (isFloorSpace(value->type) ? stageInLocal : identity);
        return composePose(frame, value->poseInReferenceSpace);
    };
    const XrPosef objectInLocal = spaceInLocal(object);
    const XrPosef baseInLocal = spaceInLocal(base);
    location->pose = composePose(inversePose(baseInLocal), objectInLocal);
    if (sameReferenceType) {
        location->locationFlags =
            XR_SPACE_LOCATION_POSITION_VALID_BIT |
            XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    } else {
        location->locationFlags =
            XR_SPACE_LOCATION_ORIENTATION_VALID_BIT |
            XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT;
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrLocateViewsImpl(
    XrSession session, const XrViewLocateInfo* locateInfo, XrViewState* viewState,
    std::uint32_t capacity, std::uint32_t* count, XrView* views
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (locateInfo == nullptr || locateInfo->type != XR_TYPE_VIEW_LOCATE_INFO ||
        viewState == nullptr || viewState->type != XR_TYPE_VIEW_STATE ||
        count == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!owner->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (locateInfo->viewConfigurationType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    XrSpace_T* base = getSpace(locateInfo->space);
    if (base == nullptr || base->owner != session || base->isActionSpace) {
        return XR_ERROR_HANDLE_INVALID;
    }
    const bool isFloorBase =
        base->type == XR_REFERENCE_SPACE_TYPE_STAGE ||
        base->type == XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR;
    if (base->type != XR_REFERENCE_SPACE_TYPE_LOCAL &&
        base->type != XR_REFERENCE_SPACE_TYPE_VIEW &&
        !isFloorBase) {
        return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    }
    if (locateInfo->displayTime <= 0) return XR_ERROR_TIME_INVALID;
    *count = 2;
    if (capacity == 0) return XR_SUCCESS;
    if (views == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < 2) return XR_ERROR_SIZE_INSUFFICIENT;
    for (std::uint32_t eye = 0; eye < 2; ++eye) {
        if (views[eye].type != XR_TYPE_VIEW) return XR_ERROR_VALIDATION_FAILURE;
    }

    const XrPosef identity{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
    XrPosef headInBase = identity;
    if (base->type == XR_REFERENCE_SPACE_TYPE_LOCAL || isFloorBase) {
        XrQuaternionf headOrientation{};
        if (readFreshHeadOrientation(&headOrientation)) {
            XrPosef headInWorld = identity;
            headInWorld.orientation = headOrientation;
            if (isFloorBase) {
                headInWorld.position.y = kDefaultStageEyeHeightMeters;
            }
            headInBase = composePose(inversePose(base->poseInReferenceSpace), headInWorld);
            viewState->viewStateFlags =
                XR_VIEW_STATE_ORIENTATION_VALID_BIT |
                XR_VIEW_STATE_ORIENTATION_TRACKED_BIT |
                (isFloorBase ? XR_VIEW_STATE_POSITION_VALID_BIT : 0u);
        } else {
            // The stereo eye separation is still known, but the camera-relative
            // orientation in LOCAL space is not. Do not invent a tracked pose.
            viewState->viewStateFlags = 0;
        }
    } else {
        // The VIEW frame follows the head, so head orientation cancels when
        // reporting the eyes relative to that same frame.
        headInBase = inversePose(base->poseInReferenceSpace);
        viewState->viewStateFlags =
            XR_VIEW_STATE_POSITION_VALID_BIT |
            XR_VIEW_STATE_ORIENTATION_VALID_BIT;
    }

    for (std::uint32_t eye = 0; eye < 2; ++eye) {
        XrPosef eyeInView = identity;
        eyeInView.position.x = eye == 0 ? -kHalfIpdMeters : kHalfIpdMeters;
        views[eye].pose = composePose(headInBase, eyeInView);
        views[eye].fov = {-0.785398163f, 0.785398163f, 0.785398163f, -0.785398163f};
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateSwapchainFormats(
    XrSession session, std::uint32_t capacity, std::uint32_t* count,
    std::int64_t* formats
) {
    if (getSession(session) == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    constexpr std::array<std::int64_t, 5> supported{{
        static_cast<std::int64_t>(GL_SRGB8_ALPHA8),
        static_cast<std::int64_t>(GL_RGBA8),
        static_cast<std::int64_t>(GL_DEPTH24_STENCIL8),
        static_cast<std::int64_t>(GL_DEPTH_COMPONENT24),
        static_cast<std::int64_t>(GL_DEPTH_COMPONENT16)
    }};
    *count = static_cast<std::uint32_t>(supported.size());
    if (capacity == 0) return XR_SUCCESS;
    if (formats == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const std::uint32_t fillCount = std::min(capacity, *count);
    for (std::uint32_t i = 0; i < fillCount; ++i) {
        formats[i] = supported[i];
    }
    return capacity < *count ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateSwapchainImpl(
    XrSession session, const XrSwapchainCreateInfo* createInfo, XrSwapchain* swapchain
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || swapchain == nullptr ||
        createInfo->type != XR_TYPE_SWAPCHAIN_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *swapchain = XR_NULL_HANDLE;
    if (!graphicsContextIsCurrent(owner)) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    const bool colorFormat = isColorSwapchainFormat(createInfo->format);
    const bool depthFormat = isDepthSwapchainFormat(createInfo->format);
    if (!colorFormat && !depthFormat) {
        return XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED;
    }
    constexpr XrSwapchainUsageFlags kAllowedCommonUsage =
        XR_SWAPCHAIN_USAGE_SAMPLED_BIT |
        XR_SWAPCHAIN_USAGE_TRANSFER_SRC_BIT |
        XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT |
        XR_SWAPCHAIN_USAGE_MUTABLE_FORMAT_BIT;
    if (colorFormat) {
        if ((createInfo->usageFlags & XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT) == 0 ||
            (createInfo->usageFlags & ~(XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT |
                                        kAllowedCommonUsage)) != 0) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
    } else {
        if ((createInfo->usageFlags & XR_SWAPCHAIN_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT) == 0 ||
            (createInfo->usageFlags & ~(XR_SWAPCHAIN_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT |
                                        kAllowedCommonUsage)) != 0) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
    }
    if (createInfo->width == 0 || createInfo->height == 0 ||
        createInfo->width > kMaximumEyeWidth || createInfo->height > kMaximumEyeHeight ||
        createInfo->sampleCount != 1 || createInfo->faceCount != 1 ||
        (createInfo->arraySize != 1 && createInfo->arraySize != 2) ||
        createInfo->mipCount != 1) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrSwapchain_T* created = new (std::nothrow) XrSwapchain_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = session;
    created->format = createInfo->format;
    created->width = createInfo->width;
    created->height = createInfo->height;
    created->arraySize = createInfo->arraySize;
    created->isDepth = depthFormat;
    for (std::uint32_t i = 0; i < created->imageCount; ++i) {
        if (!createGlesTexture(created->width, created->height, created->format,
                               created->arraySize, &created->images[i])) {
            for (std::uint32_t j = 0; j < i; ++j) destroyGlesTexture(created->images[j]);
            delete created;
            return XR_ERROR_RUNTIME_FAILURE;
        }
    }
    const XrSwapchain handle = makeSwapchainHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_swapchainMutex);
        g_swapchains.emplace(handle, created);
    }
    owner->swapchains.insert(handle);
    *swapchain = handle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroySwapchain(XrSwapchain swapchain) {
    XrSwapchain_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_swapchainMutex);
        const auto found = g_swapchains.find(swapchain);
        if (found == g_swapchains.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        if (object->acquiredIndex != std::numeric_limits<std::uint32_t>::max()) {
            return XR_ERROR_CALL_ORDER_INVALID;
        }
        g_swapchains.erase(found);
    }
    XrSession_T* owner = getSession(object->owner);
    if (owner != nullptr) owner->swapchains.erase(swapchain);
    for (GLuint texture : object->images) destroyGlesTexture(texture);
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateSwapchainImages(
    XrSwapchain swapchain, std::uint32_t capacity, std::uint32_t* count,
    XrSwapchainImageBaseHeader* images
) {
    XrSwapchain_T* object = getSwapchain(swapchain);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = object->imageCount;
    if (capacity == 0) return XR_SUCCESS;
    if (images == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < object->imageCount) return XR_ERROR_SIZE_INSUFFICIENT;
    auto* glesImages = reinterpret_cast<XrSwapchainImageOpenGLESKHR*>(images);
    for (std::uint32_t i = 0; i < object->imageCount; ++i) {
        if (glesImages[i].type != XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
        glesImages[i].image = object->images[i];
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrAcquireSwapchainImageImpl(
    XrSwapchain swapchain, const XrSwapchainImageAcquireInfo* acquireInfo,
    std::uint32_t* index
) {
    XrSwapchain_T* object = getSwapchain(swapchain);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (acquireInfo == nullptr ||
        acquireInfo->type != XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO ||
        index == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (object->acquiredIndex != std::numeric_limits<std::uint32_t>::max()) {
        return XR_ERROR_CALL_ORDER_INVALID;
    }
    object->acquiredIndex = object->nextAcquire;
    object->nextAcquire = (object->nextAcquire + 1) % object->imageCount;
    object->imageWaited = false;
    *index = object->acquiredIndex;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrWaitSwapchainImageImpl(
    XrSwapchain swapchain, const XrSwapchainImageWaitInfo* waitInfo
) {
    XrSwapchain_T* object = getSwapchain(swapchain);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (waitInfo == nullptr || waitInfo->type != XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (object->acquiredIndex == std::numeric_limits<std::uint32_t>::max() ||
        object->imageWaited) return XR_ERROR_CALL_ORDER_INVALID;
    object->imageWaited = true;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrReleaseSwapchainImageImpl(
    XrSwapchain swapchain, const XrSwapchainImageReleaseInfo* releaseInfo
) {
    XrSwapchain_T* object = getSwapchain(swapchain);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (releaseInfo == nullptr ||
        releaseInfo->type != XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (object->acquiredIndex == std::numeric_limits<std::uint32_t>::max() ||
        !object->imageWaited) return XR_ERROR_CALL_ORDER_INVALID;
    object->lastReleasedIndex = object->acquiredIndex;
    object->acquiredIndex = std::numeric_limits<std::uint32_t>::max();
    object->imageWaited = false;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrResultToString(
    XrInstance instance, XrResult value, char buffer[XR_MAX_RESULT_STRING_SIZE]
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (buffer == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const char* label = "XR_RESULT_UNKNOWN";
    switch (value) {
        case XR_SUCCESS: label = "XR_SUCCESS"; break;
        case XR_EVENT_UNAVAILABLE: label = "XR_EVENT_UNAVAILABLE"; break;
        case XR_ERROR_VALIDATION_FAILURE: label = "XR_ERROR_VALIDATION_FAILURE"; break;
        case XR_ERROR_FUNCTION_UNSUPPORTED: label = "XR_ERROR_FUNCTION_UNSUPPORTED"; break;
        case XR_ERROR_EXTENSION_NOT_PRESENT: label = "XR_ERROR_EXTENSION_NOT_PRESENT"; break;
        case XR_ERROR_FEATURE_UNSUPPORTED: label = "XR_ERROR_FEATURE_UNSUPPORTED"; break;
        case XR_ERROR_FORM_FACTOR_UNSUPPORTED: label = "XR_ERROR_FORM_FACTOR_UNSUPPORTED"; break;
        case XR_ERROR_SYSTEM_INVALID: label = "XR_ERROR_SYSTEM_INVALID"; break;
        case XR_ERROR_HANDLE_INVALID: label = "XR_ERROR_HANDLE_INVALID"; break;
        case XR_ERROR_SESSION_NOT_RUNNING: label = "XR_ERROR_SESSION_NOT_RUNNING"; break;
        case XR_ERROR_CALL_ORDER_INVALID: label = "XR_ERROR_CALL_ORDER_INVALID"; break;
        default: break;
    }
    std::snprintf(buffer, XR_MAX_RESULT_STRING_SIZE, "%s", label);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrStructureTypeToString(
    XrInstance instance, XrStructureType value, char buffer[XR_MAX_STRUCTURE_NAME_SIZE]
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (buffer == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const char* label = "XR_TYPE_UNKNOWN";
    switch (value) {
        case XR_TYPE_INSTANCE_CREATE_INFO: label = "XR_TYPE_INSTANCE_CREATE_INFO"; break;
        case XR_TYPE_INSTANCE_PROPERTIES: label = "XR_TYPE_INSTANCE_PROPERTIES"; break;
        case XR_TYPE_SYSTEM_GET_INFO: label = "XR_TYPE_SYSTEM_GET_INFO"; break;
        case XR_TYPE_SYSTEM_PROPERTIES: label = "XR_TYPE_SYSTEM_PROPERTIES"; break;
        case XR_TYPE_VIEW_CONFIGURATION_PROPERTIES: label = "XR_TYPE_VIEW_CONFIGURATION_PROPERTIES"; break;
        case XR_TYPE_VIEW_CONFIGURATION_VIEW: label = "XR_TYPE_VIEW_CONFIGURATION_VIEW"; break;
        case XR_TYPE_SESSION_CREATE_INFO: label = "XR_TYPE_SESSION_CREATE_INFO"; break;
        case XR_TYPE_FRAME_STATE: label = "XR_TYPE_FRAME_STATE"; break;
        case XR_TYPE_FRAME_WAIT_INFO: label = "XR_TYPE_FRAME_WAIT_INFO"; break;
        case XR_TYPE_FRAME_BEGIN_INFO: label = "XR_TYPE_FRAME_BEGIN_INFO"; break;
        case XR_TYPE_FRAME_END_INFO: label = "XR_TYPE_FRAME_END_INFO"; break;
        case XR_TYPE_SWAPCHAIN_CREATE_INFO: label = "XR_TYPE_SWAPCHAIN_CREATE_INFO"; break;
        default: break;
    }
    std::snprintf(buffer, XR_MAX_STRUCTURE_NAME_SIZE, "%s", label);
    return XR_SUCCESS;
}


bool readFixedName(const char* value, std::size_t capacity, std::string* output) {
    if (value == nullptr || output == nullptr || capacity == 0) return false;
    const auto* terminator = static_cast<const char*>(std::memchr(value, '\0', capacity));
    if (terminator == nullptr || terminator == value) return false;
    output->assign(value, static_cast<std::size_t>(terminator - value));
    return true;
}

bool validActionIdentifier(const std::string& value) {
    if (value.empty()) return false;
    for (const unsigned char character : value) {
        const bool allowed =
            (character >= 'a' && character <= 'z') ||
            (character >= '0' && character <= '9') ||
            character == '_';
        if (!allowed) return false;
    }
    return true;
}

bool validOpenXrPath(const std::string& value) {
    if (value.size() < 2 || value.size() >= XR_MAX_PATH_LENGTH ||
        value.front() != '/' || value.back() == '/') return false;
    bool previousSlash = false;
    for (std::size_t index = 1; index < value.size(); ++index) {
        const unsigned char character = static_cast<unsigned char>(value[index]);
        if (character == '/') {
            if (previousSlash) return false;
            previousSlash = true;
            continue;
        }
        previousSlash = false;
        const bool allowed =
            (character >= 'a' && character <= 'z') ||
            (character >= '0' && character <= '9') ||
            character == '_' || character == '-' || character == '.';
        if (!allowed) return false;
    }
    return !previousSlash;
}

bool pathBelongsToInstance(XrInstance instance, XrPath path, std::string* value = nullptr) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr || path == XR_NULL_PATH) return false;
    const auto found = owner->pathStrings.find(path);
    if (found == owner->pathStrings.end()) return false;
    if (value != nullptr) *value = found->second;
    return true;
}

bool validUserPath(XrInstance instance, XrPath path) {
    std::string value;
    return pathBelongsToInstance(instance, path, &value) &&
        value.compare(0, 6, "/user/") == 0;
}

bool validActionSubactionPath(const XrAction_T* action, XrPath path) {
    if (action == nullptr) return false;
    if (path == XR_NULL_PATH) return true;
    return std::find(action->subactionPaths.begin(), action->subactionPaths.end(), path) !=
        action->subactionPaths.end();
}
int handIndexFromPath(const std::string& path) {
    if (path.compare(0, 15, "/user/hand/left") == 0) return 0;
    if (path.compare(0, 16, "/user/hand/right") == 0) return 1;
    return -1;
}
EndXrControllerInput readControllerInput(int hand) {
    if (hand < 0 || hand > 1) return {};
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    auto input = g_controllerInputs[static_cast<std::size_t>(hand)];
    if (!input.active || input.updatedAt.time_since_epoch().count() == 0 ||
        std::chrono::steady_clock::now() - input.updatedAt > std::chrono::milliseconds(300)) return {};
    return input;
}

struct EndXrHandTrackingSample {
    bool active = false;
    XrPosef palmPose{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
    std::array<float, XR_HAND_JOINT_COUNT_EXT * 8> joints{};
};

EndXrHandTrackingSample readFreshHandTrackingInput(int hand) {
    EndXrHandTrackingSample result{};
    if (hand < 0 || hand > 1) return result;
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    const auto& input = g_controllerInputs[static_cast<std::size_t>(hand)];
    if (!input.poseActive || !input.handJointsActive ||
        input.updatedAt.time_since_epoch().count() == 0 ||
        std::chrono::steady_clock::now() - input.updatedAt > std::chrono::milliseconds(300)) {
        return result;
    }
    result.active = true;
    result.palmPose = input.gripPose;
    result.joints = input.handJoints;
    return result;
}

#if defined(__ANDROID__)
bool refreshExternalControllerInputImpl(XrInstance_T* instance) {
    if (instance == nullptr || !instance->androidExtensionEnabled ||
        instance->applicationVm == nullptr || instance->applicationActivity == nullptr) {
        return false;
    }

    // This function is called only by the per-instance polling worker. Binder
    // and ContentProvider latency must never be paid by xrLocateViews,
    // xrLocateSpace or xrSyncActions on an application's frame/input thread.
    JNIEnv* env = nullptr;
    bool attachedHere = false;
    const jint envStatus = instance->applicationVm->GetEnv(
        reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (envStatus == JNI_EDETACHED) {
        if (instance->applicationVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            endxrClearControllerInput();
            endxrClearHeadOrientation();
            endxrClearHandPoses();
            return false;
        }
        attachedHere = true;
    } else if (envStatus != JNI_OK || env == nullptr) {
        endxrClearControllerInput();
        endxrClearHeadOrientation();
        endxrClearHandPoses();
        return false;
    }

    struct InputJniCache {
        JavaVM* vm = nullptr;
        jobject controllerUri = nullptr;
        jmethodID getContentResolver = nullptr;
        jmethodID query = nullptr;
        jmethodID moveToFirst = nullptr;
        jmethodID getColumnIndex = nullptr;
        jmethodID getInt = nullptr;
        jmethodID getFloat = nullptr;
        jmethodID getBlob = nullptr;
        jmethodID close = nullptr;
    };
    static std::mutex g_inputJniCacheMutex;
    static InputJniCache g_inputJniCache;
    InputJniCache jni{};
    {
        std::lock_guard<std::mutex> lock(g_inputJniCacheMutex);
        if (g_inputJniCache.vm != instance->applicationVm || g_inputJniCache.controllerUri == nullptr) {
            if (g_inputJniCache.controllerUri != nullptr &&
                g_inputJniCache.vm == instance->applicationVm) {
                env->DeleteGlobalRef(g_inputJniCache.controllerUri);
            }
            g_inputJniCache = {};
            if (env->PushLocalFrame(32) >= 0) {
                jclass contextClass = env->FindClass("android/content/Context");
                jclass uriClass = env->FindClass("android/net/Uri");
                jclass resolverClass = env->FindClass("android/content/ContentResolver");
                jclass cursorClass = env->FindClass("android/database/Cursor");
                if (contextClass != nullptr && uriClass != nullptr &&
                    resolverClass != nullptr && cursorClass != nullptr &&
                    !env->ExceptionCheck()) {
                    jmethodID getContentResolver = env->GetMethodID(
                        contextClass, "getContentResolver",
                        "()Landroid/content/ContentResolver;");
                    jmethodID parseUri = env->GetStaticMethodID(
                        uriClass, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
                    jmethodID query = env->GetMethodID(
                        resolverClass, "query",
                        "(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;");
                    jmethodID moveToFirst = env->GetMethodID(cursorClass, "moveToFirst", "()Z");
                    jmethodID getColumnIndex = env->GetMethodID(
                        cursorClass, "getColumnIndexOrThrow", "(Ljava/lang/String;)I");
                    jmethodID getInt = env->GetMethodID(cursorClass, "getInt", "(I)I");
                    jmethodID getFloat = env->GetMethodID(cursorClass, "getFloat", "(I)F");
                    jmethodID getBlob = env->GetMethodID(cursorClass, "getBlob", "(I)[B");
                    jmethodID close = env->GetMethodID(cursorClass, "close", "()V");
                    jstring uriText = env->NewStringUTF(
                        "content://com.noyclic.questphonexr.endxrinput/controller_state");
                    jobject localUri = (parseUri != nullptr && uriText != nullptr)
                        ? env->CallStaticObjectMethod(uriClass, parseUri, uriText)
                        : nullptr;
                    jobject globalUri = (localUri != nullptr && !env->ExceptionCheck())
                        ? env->NewGlobalRef(localUri)
                        : nullptr;
                    if (!env->ExceptionCheck() && globalUri != nullptr &&
                        getContentResolver != nullptr && query != nullptr &&
                        moveToFirst != nullptr && getColumnIndex != nullptr &&
                        getInt != nullptr && getFloat != nullptr &&
                        getBlob != nullptr && close != nullptr) {
                        g_inputJniCache.vm = instance->applicationVm;
                        g_inputJniCache.controllerUri = globalUri;
                        g_inputJniCache.getContentResolver = getContentResolver;
                        g_inputJniCache.query = query;
                        g_inputJniCache.moveToFirst = moveToFirst;
                        g_inputJniCache.getColumnIndex = getColumnIndex;
                        g_inputJniCache.getInt = getInt;
                        g_inputJniCache.getFloat = getFloat;
                        g_inputJniCache.getBlob = getBlob;
                        g_inputJniCache.close = close;
                    } else if (globalUri != nullptr) {
                        env->DeleteGlobalRef(globalUri);
                    }
                }
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->PopLocalFrame(nullptr);
            }
        }
        jni = g_inputJniCache;
    }

    bool success = false;
    if (jni.controllerUri != nullptr && env->PushLocalFrame(128) >= 0) {
        do {
            jobject resolver = env->CallObjectMethod(
                instance->applicationActivity, jni.getContentResolver);
            if (resolver == nullptr || env->ExceptionCheck()) break;

            jobject cursor = env->CallObjectMethod(
                resolver, jni.query, jni.controllerUri, nullptr, nullptr, nullptr, nullptr);
            if (cursor == nullptr || env->ExceptionCheck()) break;

            const jboolean hasRow = env->CallBooleanMethod(cursor, jni.moveToFirst);
            if (env->ExceptionCheck() || hasRow != JNI_TRUE) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->CallVoidMethod(cursor, jni.close);
                instance->cursorColumnsCached = false;
                break;
            }

            static constexpr std::array<const char*, 40> kInputColumnNames{{
                "stale", "left_active", "left_trigger", "left_grip", "left_stick_x",
                "left_stick_y", "left_buttons", "right_active", "right_trigger", "right_grip",
                "right_stick_x", "right_stick_y", "right_buttons", "head_orientation_active",
                "head_orientation_stale", "head_orientation_x", "head_orientation_y",
                "head_orientation_z", "head_orientation_w", "hand_pose_stale",
                "left_pose_active", "left_pose_x", "left_pose_y", "left_pose_z",
                "left_pose_qx", "left_pose_qy", "left_pose_qz", "left_pose_qw",
                "right_pose_active", "right_pose_x", "right_pose_y", "right_pose_z",
                "right_pose_qx", "right_pose_qy", "right_pose_qz", "right_pose_qw",
                "left_joints_active", "left_joints", "right_joints_active", "right_joints"
            }};
            if (!instance->cursorColumnsCached) {
                bool cachedOk = true;
                for (std::size_t col = 0; col < kInputColumnNames.size(); ++col) {
                    jstring columnName = env->NewStringUTF(kInputColumnNames[col]);
                    if (columnName == nullptr || env->ExceptionCheck()) {
                        cachedOk = false;
                        break;
                    }
                    const jint idx = env->CallIntMethod(cursor, jni.getColumnIndex, columnName);
                    env->DeleteLocalRef(columnName);
                    if (env->ExceptionCheck() || idx < 0) {
                        cachedOk = false;
                        break;
                    }
                    instance->cursorColumnIndices[col] = idx;
                }
                if (!cachedOk) {
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    env->CallVoidMethod(cursor, jni.close);
                    break;
                }
                instance->cursorColumnsCached = true;
            }

            auto readIntAt = [&](std::size_t col, jint* value) -> bool {
                *value = env->CallIntMethod(cursor, jni.getInt, instance->cursorColumnIndices[col]);
                return !env->ExceptionCheck();
            };
            auto readFloatAt = [&](std::size_t col, jfloat* value) -> bool {
                *value = env->CallFloatMethod(cursor, jni.getFloat, instance->cursorColumnIndices[col]);
                return !env->ExceptionCheck();
            };
            auto readJointBlobAt = [&](std::size_t col,
                std::array<float, XR_HAND_JOINT_COUNT_EXT * 8>* values) -> bool {
                if (values == nullptr) return false;
                auto blob = reinterpret_cast<jbyteArray>(
                    env->CallObjectMethod(cursor, jni.getBlob, instance->cursorColumnIndices[col]));
                if (blob == nullptr || env->ExceptionCheck()) return false;
                const jsize byteCount = env->GetArrayLength(blob);
                if (byteCount != static_cast<jsize>(values->size() * sizeof(float))) {
                    return false;
                }
                jbyte bytes[XR_HAND_JOINT_COUNT_EXT * 8 * sizeof(float)]{};
                env->GetByteArrayRegion(blob, 0, byteCount, bytes);
                if (env->ExceptionCheck()) return false;
                std::memcpy(values->data(), bytes, sizeof(bytes));
                return true;
            };

            jint stale = 1;
            jint leftActive = 0, leftButtons = 0, rightActive = 0, rightButtons = 0;
            jint headActive = 0, headStale = 1;
            jint handPoseStale = 1, leftPoseActive = 0, rightPoseActive = 0;
            jint leftJointsActive = 0, rightJointsActive = 0;
            std::array<float, XR_HAND_JOINT_COUNT_EXT * 8> leftJoints{};
            std::array<float, XR_HAND_JOINT_COUNT_EXT * 8> rightJoints{};
            jfloat leftTrigger = 0, leftGrip = 0, leftStickX = 0, leftStickY = 0;
            jfloat rightTrigger = 0, rightGrip = 0, rightStickX = 0, rightStickY = 0;
            jfloat headX = 0, headY = 0, headZ = 0, headW = 1;
            jfloat leftPoseX = 0, leftPoseY = 0, leftPoseZ = 0;
            jfloat leftPoseQx = 0, leftPoseQy = 0, leftPoseQz = 0, leftPoseQw = 1;
            jfloat rightPoseX = 0, rightPoseY = 0, rightPoseZ = 0;
            jfloat rightPoseQx = 0, rightPoseQy = 0, rightPoseQz = 0, rightPoseQw = 1;
            const bool columnsRead =
                readIntAt(0, &stale) &&
                readIntAt(1, &leftActive) &&
                readFloatAt(2, &leftTrigger) &&
                readFloatAt(3, &leftGrip) &&
                readFloatAt(4, &leftStickX) &&
                readFloatAt(5, &leftStickY) &&
                readIntAt(6, &leftButtons) &&
                readIntAt(7, &rightActive) &&
                readFloatAt(8, &rightTrigger) &&
                readFloatAt(9, &rightGrip) &&
                readFloatAt(10, &rightStickX) &&
                readFloatAt(11, &rightStickY) &&
                readIntAt(12, &rightButtons) &&
                readIntAt(13, &headActive) &&
                readIntAt(14, &headStale) &&
                readFloatAt(15, &headX) &&
                readFloatAt(16, &headY) &&
                readFloatAt(17, &headZ) &&
                readFloatAt(18, &headW) &&
                readIntAt(19, &handPoseStale) &&
                readIntAt(20, &leftPoseActive) &&
                readFloatAt(21, &leftPoseX) &&
                readFloatAt(22, &leftPoseY) &&
                readFloatAt(23, &leftPoseZ) &&
                readFloatAt(24, &leftPoseQx) &&
                readFloatAt(25, &leftPoseQy) &&
                readFloatAt(26, &leftPoseQz) &&
                readFloatAt(27, &leftPoseQw) &&
                readIntAt(28, &rightPoseActive) &&
                readFloatAt(29, &rightPoseX) &&
                readFloatAt(30, &rightPoseY) &&
                readFloatAt(31, &rightPoseZ) &&
                readFloatAt(32, &rightPoseQx) &&
                readFloatAt(33, &rightPoseQy) &&
                readFloatAt(34, &rightPoseQz) &&
                readFloatAt(35, &rightPoseQw) &&
                readIntAt(36, &leftJointsActive) &&
                readJointBlobAt(37, &leftJoints) &&
                readIntAt(38, &rightJointsActive) &&
                readJointBlobAt(39, &rightJoints);
            if (!columnsRead) instance->cursorColumnsCached = false;
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->CallVoidMethod(cursor, jni.close);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
            if (!columnsRead) break;

            if (stale != 0) {
                endxrClearControllerInput();
            } else {
                endxrSetControllerInput(0, static_cast<std::uint32_t>(leftActive != 0),
                    leftTrigger, leftGrip, leftStickX, leftStickY,
                    static_cast<std::uint32_t>(leftButtons));
                endxrSetControllerInput(1, static_cast<std::uint32_t>(rightActive != 0),
                    rightTrigger, rightGrip, rightStickX, rightStickY,
                    static_cast<std::uint32_t>(rightButtons));
            }
            if (headStale != 0 || headActive == 0) {
                endxrClearHeadOrientation();
            } else {
                endxrSetHeadOrientation(headX, headY, headZ, headW);
            }
            if (handPoseStale != 0) {
                endxrClearHandPoses();
            } else {
                endxrSetHandPose(0, static_cast<std::uint32_t>(leftPoseActive != 0),
                    leftPoseX, leftPoseY, leftPoseZ,
                    leftPoseQx, leftPoseQy, leftPoseQz, leftPoseQw);
                endxrSetHandPose(1, static_cast<std::uint32_t>(rightPoseActive != 0),
                    rightPoseX, rightPoseY, rightPoseZ,
                    rightPoseQx, rightPoseQy, rightPoseQz, rightPoseQw);
                endxrSetHandJointData(0, static_cast<std::uint32_t>(leftJointsActive != 0),
                    leftJoints.data(), static_cast<std::uint32_t>(leftJoints.size()));
                endxrSetHandJointData(1, static_cast<std::uint32_t>(rightJointsActive != 0),
                    rightJoints.data(), static_cast<std::uint32_t>(rightJoints.size()));
            }
            success = true;
        } while (false);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            endxrClearControllerInput();
            endxrClearHeadOrientation();
            endxrClearHandPoses();
        }
        env->PopLocalFrame(nullptr);
    } else {
        if (env->ExceptionCheck()) env->ExceptionClear();
        endxrClearControllerInput();
        endxrClearHeadOrientation();
        endxrClearHandPoses();
    }

    if (!success) {
        endxrClearControllerInput();
        endxrClearHeadOrientation();
        endxrClearHandPoses();
    }
    if (attachedHere) instance->applicationVm->DetachCurrentThread();
    return success;
}
#endif

int interactionProfilePriority(const std::string& profile) {
    // When camera joints are fresh, prefer the standard hand-interaction profile.
    // Otherwise use the most Quest-like controller profile the app suggested.
    if (profile == "/interaction_profiles/ext/hand_interaction_ext") return -1;
    if (profile == "/interaction_profiles/meta/touch_pro_controller") return 0;
    if (profile == "/interaction_profiles/oculus/touch_controller") return 1;
    if (profile == "/interaction_profiles/meta/touch_controller_plus") return 2;
    if (profile == "/interaction_profiles/htc/vive_controller") return 3;
    if (profile == "/interaction_profiles/khr/simple_controller") return 4;
    return 10;
}

XrPath currentProfileForHand(XrSession_T* session, int hand) {
    if (session == nullptr || hand < 0 || hand > 1) return XR_NULL_PATH;
    const auto& handInput = session->synchronizedControllerInputs[static_cast<std::size_t>(hand)];
    if (!handInput.active) return XR_NULL_PATH;
    XrInstance_T* instance = getInstance(session->owner);
    if (instance == nullptr) return XR_NULL_PATH;
    const std::string prefix = hand == 0 ? "/user/hand/left/" : "/user/hand/right/";
    std::vector<std::pair<std::string, XrPath>> profiles;
    for (const auto& entry : instance->suggestedBindings) {
        for (const auto& binding : entry.second) {
            std::string bindingPath, profilePath;
            if (pathBelongsToInstance(session->owner, binding.binding, &bindingPath) &&
                bindingPath.compare(0, prefix.size(), prefix) == 0 &&
                pathBelongsToInstance(session->owner, entry.first, &profilePath)) {
                profiles.emplace_back(profilePath, entry.first);
                break;
            }
        }
    }
    std::sort(profiles.begin(), profiles.end(),
        [](const auto& a, const auto& b) {
            const int aPriority = interactionProfilePriority(a.first);
            const int bPriority = interactionProfilePriority(b.first);
            if (aPriority != bPriority) return aPriority < bPriority;
            return a.first < b.first;
        });
    return profiles.empty() ? XR_NULL_PATH : profiles.front().second;
}
float handAimActivationStrength(const XrControllerInputSample& input) {
    if (!input.handJointsActive) return 0.0f;
    auto jointPosition = [&input](std::uint32_t joint) {
        const std::size_t offset = static_cast<std::size_t>(joint) * 8u;
        return XrVector3f{
            input.handJoints[offset],
            input.handJoints[offset + 1u],
            input.handJoints[offset + 2u]
        };
    };
    auto distance = [](const XrVector3f& a, const XrVector3f& b) {
        const float dx = a.x - b.x;
        const float dy = a.y - b.y;
        const float dz = a.z - b.z;
        return std::sqrt(dx * dx + dy * dy + dz * dz);
    };
    auto foldRatio = [&](std::uint32_t metacarpal, std::uint32_t tip, float* ratio) {
        if (ratio == nullptr) return false;
        const XrVector3f palm = jointPosition(XR_HAND_JOINT_PALM_EXT);
        const XrVector3f base = jointPosition(metacarpal);
        const XrVector3f fingertip = jointPosition(tip);
        const float palmLength = distance(palm, base);
        const float foldedLength = distance(base, fingertip);
        if (!std::isfinite(palmLength) || !std::isfinite(foldedLength) ||
            palmLength < 0.015f || foldedLength < 0.001f || foldedLength > 0.5f) {
            return false;
        }
        *ratio = foldedLength / palmLength;
        return std::isfinite(*ratio) && *ratio > 0.1f && *ratio < 6.0f;
    };

    float indexFold = 0.0f;
    if (!foldRatio(
            XR_HAND_JOINT_INDEX_METACARPAL_EXT,
            XR_HAND_JOINT_INDEX_TIP_EXT,
            &indexFold)) {
        return 0.0f;
    }

    // EndXR's runtime-defined aim gesture is a pointing index finger while
    // the other long fingers remain curled. This is intentionally independent
    // from pinch_ext/value and grasp_ext/value.
    const float indexExtended = std::clamp((indexFold - 1.0f) / 0.65f, 0.0f, 1.0f);
    constexpr std::array<std::pair<std::uint32_t, std::uint32_t>, 3> supportFingers{{
        {XR_HAND_JOINT_MIDDLE_METACARPAL_EXT, XR_HAND_JOINT_MIDDLE_TIP_EXT},
        {XR_HAND_JOINT_RING_METACARPAL_EXT, XR_HAND_JOINT_RING_TIP_EXT},
        {XR_HAND_JOINT_LITTLE_METACARPAL_EXT, XR_HAND_JOINT_LITTLE_TIP_EXT}
    }};
    float supportCurl = 0.0f;
    std::uint32_t validSupportCount = 0;
    for (const auto& finger : supportFingers) {
        float ratio = 0.0f;
        if (!foldRatio(finger.first, finger.second, &ratio)) continue;
        supportCurl += std::clamp((1.60f - ratio) / 0.95f, 0.0f, 1.0f);
        ++validSupportCount;
    }
    if (validSupportCount < 2) return 0.0f;
    supportCurl /= static_cast<float>(validSupportCount);
    return std::clamp(indexExtended * supportCurl, 0.0f, 1.0f);
}

struct ResolvedActionInput {
    bool active = false, booleanValue = false;
    float scalarValue = 0.0f;
    XrVector2f vectorValue{0.0f, 0.0f};
};
ResolvedActionInput resolveActionInput(XrSession_T* session, XrAction_T* action, XrPath requestedHand) {
    ResolvedActionInput result{};
    if (session == nullptr || action == nullptr) return result;
    const bool actionSetActive = std::any_of(session->activeActionSets.begin(),
        session->activeActionSets.end(), [&](const XrActiveActionSet& active) {
            return active.actionSet == action->owner &&
                (active.subactionPath == XR_NULL_PATH || requestedHand == XR_NULL_PATH ||
                 active.subactionPath == requestedHand);
        });
    if (!actionSetActive) return result;
    XrInstance_T* instance = getInstance(session->owner);
    if (instance == nullptr) return result;
    const auto requested = instance->pathStrings.find(requestedHand);
    const int firstHand = requestedHand == XR_NULL_PATH ? 0 :
        (requested == instance->pathStrings.end() ? -1 : handIndexFromPath(requested->second));
    const int lastHand = requestedHand == XR_NULL_PATH ? 1 : firstHand;
    if (firstHand < 0) return result;
    for (int hand = firstHand; hand <= lastHand; ++hand) {
        const XrPath handPath = hand == 0
            ? (instance->pathHandles.count("/user/hand/left") ? instance->pathHandles.at("/user/hand/left") : XR_NULL_PATH)
            : (instance->pathHandles.count("/user/hand/right") ? instance->pathHandles.at("/user/hand/right") : XR_NULL_PATH);
        const bool handSetActive = std::any_of(session->activeActionSets.begin(),
            session->activeActionSets.end(), [&](const XrActiveActionSet& active) {
                return active.actionSet == action->owner &&
                    (active.subactionPath == XR_NULL_PATH || active.subactionPath == handPath);
            });
        if (!handSetActive) continue;
        const XrPath profile = currentProfileForHand(session, hand);
        const auto profileBindings = instance->suggestedBindings.find(profile);
        if (profile == XR_NULL_PATH || profileBindings == instance->suggestedBindings.end()) continue;
        const auto& input = session->synchronizedControllerInputs[static_cast<std::size_t>(hand)];
        for (const auto& binding : profileBindings->second) {
            if (binding.action != reinterpret_cast<XrAction>(action)) continue;
            std::string path;
            if (!pathBelongsToInstance(session->owner, binding.binding, &path) ||
                handIndexFromPath(path) != hand) continue;
            const auto marker = path.find("/input/");
            if (marker == std::string::npos) continue;
            const std::string component = path.substr(marker + 7);
            float scalar = 0.0f;
            bool pressed = false, recognized = true;
            XrVector2f vector{input.stickX, input.stickY};
            if (component == "select/click" || component == "trigger/click" ||
                component == "select/value" || component == "trigger/value" ||
                component == "pinch_ext/value") {
                scalar = input.trigger; pressed = scalar >= 0.5f;
            } else if (component == "aim_activate_ext/value") {
                // Hand-tracking input uses an independent index-point gesture.
                // Controller-only input maps aim activation to its trigger.
                scalar = input.handJointsActive
                    ? handAimActivationStrength(input)
                    : input.trigger;
                pressed = scalar >= 0.5f;
            } else if (component == "squeeze/click" || component == "grip/click" ||
                       component == "squeeze/value" || component == "grip/value" ||
                       component == "grasp_ext/value") {
                scalar = input.grip; pressed = scalar >= 0.5f;
            } else if (component == "pinch_ext/ready_ext" ||
                       component == "aim_activate_ext/ready_ext" ||
                       component == "grasp_ext/ready_ext") {
                // Camera-joint readiness or an active controller input can make
                // the interaction ready. This also lets controller-only runtimes
                // expose the standard hand-interaction profile without joint data.
                scalar = 1.0f;
                pressed = input.active;
            } else if (component == "thumbstick" || component == "thumbstick/x" ||
                       component == "thumbstick/y") {
                scalar = component == "thumbstick/x" ? input.stickX :
                    component == "thumbstick/y" ? input.stickY : 0.0f;
                pressed = std::abs(scalar) > 0.5f;
            } else {
                const std::uint32_t bit = component == "a/click" ? 0u :
                    component == "b/click" ? 1u : component == "x/click" ? 2u :
                    component == "y/click" ? 3u : component == "menu/click" ? 4u : 31u;
                recognized = bit < 5;
                pressed = recognized && (input.buttons & (1u << bit)) != 0;
                scalar = pressed ? 1.0f : 0.0f;
            }
            if (!recognized) continue;
            result.active = true;
            result.booleanValue = result.booleanValue || pressed;
            result.scalarValue = std::max(result.scalarValue, scalar);
            result.vectorValue = vector;
        }
    }
    return result;
}

struct ResolvedActionPose {
    bool active = false;
    XrPosef pose{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
};

XrQuaternionf normalizedQuaternion(const XrQuaternionf& value) {
    const double magnitude = std::sqrt(
        static_cast<double>(value.x) * value.x +
        static_cast<double>(value.y) * value.y +
        static_cast<double>(value.z) * value.z +
        static_cast<double>(value.w) * value.w
    );
    if (!std::isfinite(magnitude) || magnitude < 0.5 || magnitude > 1.5) {
        return {0.0f, 0.0f, 0.0f, 1.0f};
    }
    return {
        static_cast<float>(value.x / magnitude),
        static_cast<float>(value.y / magnitude),
        static_cast<float>(value.z / magnitude),
        static_cast<float>(value.w / magnitude)
    };
}

XrQuaternionf multiplyQuaternion(const XrQuaternionf& a, const XrQuaternionf& b) {
    return normalizedQuaternion({
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
    });
}

XrVector3f rotateVector(const XrQuaternionf& orientation, const XrVector3f& value) {
    const XrQuaternionf q = normalizedQuaternion(orientation);
    const XrVector3f qv{q.x, q.y, q.z};
    const XrVector3f t{
        2.0f * (qv.y * value.z - qv.z * value.y),
        2.0f * (qv.z * value.x - qv.x * value.z),
        2.0f * (qv.x * value.y - qv.y * value.x)
    };
    const XrVector3f cross{
        qv.y * t.z - qv.z * t.y,
        qv.z * t.x - qv.x * t.z,
        qv.x * t.y - qv.y * t.x
    };
    return {
        value.x + q.w * t.x + cross.x,
        value.y + q.w * t.y + cross.y,
        value.z + q.w * t.z + cross.z
    };
}

XrPosef composePose(const XrPosef& parent, const XrPosef& child) {
    const XrQuaternionf orientation = normalizedQuaternion(parent.orientation);
    const XrVector3f offset = rotateVector(orientation, child.position);
    return {
        multiplyQuaternion(orientation, child.orientation),
        {
            parent.position.x + offset.x,
            parent.position.y + offset.y,
            parent.position.z + offset.z
        }
    };
}

XrPosef inversePose(const XrPosef& value) {
    const XrQuaternionf q = normalizedQuaternion(value.orientation);
    const XrQuaternionf inverse{-q.x, -q.y, -q.z, q.w};
    const XrVector3f negative{-value.position.x, -value.position.y, -value.position.z};
    return {inverse, rotateVector(inverse, negative)};
}

ResolvedActionPose resolveActionPose(
    XrSession_T* session, XrAction_T* action, XrPath requestedHand
) {
    ResolvedActionPose result{};
    if (session == nullptr || action == nullptr) return result;

    const bool actionSetActive = std::any_of(
        session->activeActionSets.begin(), session->activeActionSets.end(),
        [&](const XrActiveActionSet& active) {
            return active.actionSet == action->owner &&
                (active.subactionPath == XR_NULL_PATH || requestedHand == XR_NULL_PATH ||
                 active.subactionPath == requestedHand);
        });
    if (!actionSetActive) return result;

    XrInstance_T* instance = getInstance(session->owner);
    if (instance == nullptr) return result;
    const auto requested = instance->pathStrings.find(requestedHand);
    const int firstHand = requestedHand == XR_NULL_PATH ? 0 :
        (requested == instance->pathStrings.end()
            ? -1 : handIndexFromPath(requested->second));
    const int lastHand = requestedHand == XR_NULL_PATH ? 1 : firstHand;
    if (firstHand < 0) return result;

    for (int hand = firstHand; hand <= lastHand; ++hand) {
        const XrPath handPath = hand == 0
            ? (instance->pathHandles.count("/user/hand/left")
                ? instance->pathHandles.at("/user/hand/left") : XR_NULL_PATH)
            : (instance->pathHandles.count("/user/hand/right")
                ? instance->pathHandles.at("/user/hand/right") : XR_NULL_PATH);
        const bool handSetActive = std::any_of(
            session->activeActionSets.begin(), session->activeActionSets.end(),
            [&](const XrActiveActionSet& active) {
                return active.actionSet == action->owner &&
                    (active.subactionPath == XR_NULL_PATH || active.subactionPath == handPath);
            });
        if (!handSetActive) continue;

        const auto& input =
            session->synchronizedControllerInputs[static_cast<std::size_t>(hand)];
        if (!input.active || !input.poseActive) continue;

        const XrPath profile = currentProfileForHand(session, hand);
        const auto profileBindings = instance->suggestedBindings.find(profile);
        if (profile == XR_NULL_PATH || profileBindings == instance->suggestedBindings.end()) {
            continue;
        }
        std::string profileName;
        if (!pathBelongsToInstance(session->owner, profile, &profileName)) continue;
        const bool handInteractionProfile =
            profileName == "/interaction_profiles/ext/hand_interaction_ext";

        auto localJointPose = [&input](std::uint32_t joint) {
            const std::size_t offset = static_cast<std::size_t>(joint) * 8u;
            XrPosef pose{};
            pose.position = {
                input.handJoints[offset],
                input.handJoints[offset + 1u],
                input.handJoints[offset + 2u]
            };
            pose.orientation = {
                input.handJoints[offset + 4u],
                input.handJoints[offset + 5u],
                input.handJoints[offset + 6u],
                input.handJoints[offset + 7u]
            };
            return pose;
        };

        for (const auto& binding : profileBindings->second) {
            if (binding.action != reinterpret_cast<XrAction>(action)) continue;
            std::string path;
            if (!pathBelongsToInstance(session->owner, binding.binding, &path) ||
                handIndexFromPath(path) != hand) continue;
            const auto marker = path.find("/input/");
            if (marker == std::string::npos) continue;
            const std::string component = path.substr(marker + 7u);

            if (component == "grip/pose") {
                result.active = true;
                result.pose = input.gripPose;
                return result;
            }

            if (component == "aim/pose") {
                // Aim is a pointing ray ahead of the palm. The camera currently
                // provides orientation but no stable shoulder/forearm ray model.
                XrPosef aimOffset{};
                aimOffset.orientation.w = 1.0f;
                aimOffset.position = {0.0f, 0.0f, -0.12f};
                result.active = true;
                result.pose = composePose(input.gripPose, aimOffset);
                return result;
            }

            if (component == "pinch_ext/pose" || component == "poke_ext/pose") {
                XrPosef interactionOffset{};
                interactionOffset.orientation.w = 1.0f;
                if (handInteractionProfile && input.handJointsActive) {
                    if (component == "poke_ext/pose") {
                        // The poke point is located at the camera-derived index fingertip.
                        interactionOffset = localJointPose(XR_HAND_JOINT_INDEX_TIP_EXT);
                    } else {
                        // Pinch uses the midpoint between thumb and index fingertips.
                        // Palm-relative orientation is a conservative, stable estimate.
                        const XrPosef thumbTip = localJointPose(XR_HAND_JOINT_THUMB_TIP_EXT);
                        const XrPosef indexTip = localJointPose(XR_HAND_JOINT_INDEX_TIP_EXT);
                        interactionOffset.position = {
                            (thumbTip.position.x + indexTip.position.x) * 0.5f,
                            (thumbTip.position.y + indexTip.position.y) * 0.5f,
                            (thumbTip.position.z + indexTip.position.z) * 0.5f
                        };
                    }
                } else {
                    // OpenXR requires the four hand-interaction pose paths on
                    // controller profiles too. Use conservative offsets in front
                    // of the grip pose until camera joints or a physical controller
                    // provides a more accurate interaction point.
                    interactionOffset.position = component == "poke_ext/pose"
                        ? XrVector3f{0.0f, 0.0f, -0.10f}
                        : XrVector3f{0.0f, 0.0f, -0.06f};
                }
                result.active = true;
                result.pose = composePose(input.gripPose, interactionOffset);
                return result;
            }
        }
    }
    return result;
}

bool locateEstimatedHandPoseImpl(
    XrSpace_T* object, XrSpace_T* base, XrPosef* poseOutput
) {
    if (object == nullptr || base == nullptr || poseOutput == nullptr ||
        !object->isActionSpace || base->isActionSpace) return false;
    XrSession_T* session = getSession(object->owner);
    if (session == nullptr) return false;
    XrAction_T* action = getAction(object->action);
    const ResolvedActionPose resolved =
        resolveActionPose(session, action, object->actionSubactionPath);
    if (!resolved.active) return false;

    XrPosef pose = composePose(resolved.pose, object->poseInReferenceSpace);
    const bool isFloorBase =
        base->type == XR_REFERENCE_SPACE_TYPE_STAGE ||
        base->type == XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR;
    if (base->type == XR_REFERENCE_SPACE_TYPE_LOCAL || isFloorBase) {
        XrQuaternionf headOrientation{};
        if (!readFreshHeadOrientation(&headOrientation)) return false;
        XrPosef headPose{};
        headPose.orientation = headOrientation;
        headPose.position = {
            0.0f,
            isFloorBase ? kDefaultStageEyeHeightMeters : 0.0f,
            0.0f
        };
        pose = composePose(headPose, pose);
    } else if (base->type != XR_REFERENCE_SPACE_TYPE_VIEW) {
        return false;
    }

    *poseOutput = composePose(inversePose(base->poseInReferenceSpace), pose);
    return true;
}

XrActionStateSnapshot getActionStateSnapshot(
    XrSession_T* session, XrAction action, XrPath subactionPath
) {
    if (session == nullptr) return {};
    const XrActionStateKey key{action, subactionPath};
    const auto found = session->actionStateSnapshots.find(key);
    return found == session->actionStateSnapshots.end()
        ? XrActionStateSnapshot{} : found->second;
}

void rebuildActionStateSnapshots(XrSession_T* session) {
    if (session == nullptr) return;
    const auto previous = session->actionStateSnapshots;
    session->actionStateSnapshots.clear();
    const XrTime syncTime = monotonicTimeNow();

    for (const XrActionSet actionSetHandle : session->attachedActionSets) {
        XrActionSet_T* actionSet = getActionSet(actionSetHandle);
        if (actionSet == nullptr) continue;
        for (const XrAction actionHandle : actionSet->actions) {
            XrAction_T* action = getAction(actionHandle);
            if (action == nullptr) continue;

            std::vector<XrPath> subactionPaths{XR_NULL_PATH};
            subactionPaths.insert(
                subactionPaths.end(),
                action->subactionPaths.begin(),
                action->subactionPaths.end());
            std::sort(subactionPaths.begin(), subactionPaths.end());
            subactionPaths.erase(
                std::unique(subactionPaths.begin(), subactionPaths.end()),
                subactionPaths.end());

            for (const XrPath subactionPath : subactionPaths) {
                const ResolvedActionInput current = resolveActionInput(
                    session, action, subactionPath);
                const XrActionStateKey key{actionHandle, subactionPath};
                const auto previousState = previous.find(key);
                const XrActionStateSnapshot oldState = previousState == previous.end()
                    ? XrActionStateSnapshot{} : previousState->second;

                XrActionStateSnapshot snapshot{};
                snapshot.active = current.active;
                snapshot.booleanValue = current.booleanValue;
                snapshot.scalarValue = current.scalarValue;
                snapshot.vectorValue = current.vectorValue;
                snapshot.booleanChanged = current.active != oldState.active ||
                    current.booleanValue != oldState.booleanValue;
                snapshot.scalarChanged = current.active != oldState.active ||
                    current.scalarValue != oldState.scalarValue;
                snapshot.vectorChanged = current.active != oldState.active ||
                    current.vectorValue.x != oldState.vectorValue.x ||
                    current.vectorValue.y != oldState.vectorValue.y;
                snapshot.booleanLastChangeTime = snapshot.booleanChanged
                    ? syncTime : oldState.booleanLastChangeTime;
                snapshot.scalarLastChangeTime = snapshot.scalarChanged
                    ? syncTime : oldState.scalarLastChangeTime;
                snapshot.vectorLastChangeTime = snapshot.vectorChanged
                    ? syncTime : oldState.vectorLastChangeTime;
                session->actionStateSnapshots.emplace(key, snapshot);
            }
        }
    }
}

XrResult validateActionForSession(
    XrSession_T* session,
    const XrActionStateGetInfo* info,
    XrActionType expectedType,
    XrAction_T** actionOutput
) {
    if (session == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (info == nullptr || info->type != XR_TYPE_ACTION_STATE_GET_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = getAction(info->action);
    if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (action->type != expectedType) return XR_ERROR_ACTION_TYPE_MISMATCH;
    XrActionSet_T* actionSet = getActionSet(action->owner);
    if (actionSet == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (actionSet->owner != session->owner || actionSet->attachedSession !=
            reinterpret_cast<XrSession>(session)) {
#if XR_PTR_SIZE != 8
        XrSession sessionHandle = XR_NULL_HANDLE;
        {
            std::lock_guard<std::mutex> lock(g_sessionMutex);
            for (const auto& entry : g_sessions) {
                if (entry.second == session) sessionHandle = entry.first;
            }
        }
        if (actionSet->owner != session->owner || actionSet->attachedSession != sessionHandle) {
            return XR_ERROR_ACTIONSET_NOT_ATTACHED;
        }
#else
        return XR_ERROR_ACTIONSET_NOT_ATTACHED;
#endif
    }
    if (!validActionSubactionPath(action, info->subactionPath)) return XR_ERROR_PATH_INVALID;
    if (!session->actionSyncCompleted) return XR_ERROR_CALL_ORDER_INVALID;
    if (!session->running || session->state != XR_SESSION_STATE_FOCUSED) {
        return XR_SESSION_NOT_FOCUSED;
    }
    if (actionOutput != nullptr) *actionOutput = action;
    return XR_SUCCESS;
}

#if defined(__ANDROID__)
constexpr auto kExternalInputPollInterval = std::chrono::milliseconds(20);
constexpr const char* kRuntimeTelemetryUri =
    "content://com.noyclic.questphonexr.endxrtelemetry/runtime_event";

void enqueueRuntimeTelemetry(
    XrInstance_T* instance,
    const char* eventName,
    std::uint64_t sessionId,
    std::uint64_t frameCount
) {
    if (instance == nullptr || eventName == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(instance->runtimeTelemetryMutex);
        auto& queue = instance->pendingRuntimeTelemetry;

        // Collapse frame milestones for the same session while Binder is
        // unavailable. This preserves lifecycle edges without growing one
        // event per 30 rendered frames.
        if (std::strcmp(eventName, "xr_end_frame") == 0) {
            const auto previousFrame = std::find_if(
                queue.rbegin(),
                queue.rend(),
                [sessionId](const PendingRuntimeTelemetry& pending) {
                    return pending.eventName == "xr_end_frame" &&
                        pending.sessionId == sessionId;
                }
            );
            if (previousFrame != queue.rend()) {
                previousFrame->frameCount = std::max(previousFrame->frameCount, frameCount);
                instance->externalInputWake.notify_one();
                return;
            }
        }

        constexpr std::size_t kMaximumPendingRuntimeEvents = 64;
        if (queue.size() >= kMaximumPendingRuntimeEvents) {
            const auto expendableFrame = std::find_if(
                queue.begin(),
                queue.end(),
                [](const PendingRuntimeTelemetry& pending) {
                    return pending.eventName == "xr_end_frame";
                }
            );
            if (expendableFrame != queue.end()) {
                queue.erase(expendableFrame);
            } else {
                // Lifecycle transitions are far less frequent than frames.
                // Only an unusually long outage with many instance/session
                // recreations can reach this fallback.
                queue.pop_front();
            }
        }
        queue.push_back(PendingRuntimeTelemetry{
            eventName,
            sessionId,
            frameCount
        });
    }
    instance->externalInputWake.notify_one();
}

void enqueueRuntimeTrace(
    XrInstance_T* instance,
    std::uint64_t sessionId,
    const char* stage,
    const char* operation,
    XrResult result,
    std::uint64_t durationNs,
    std::uint64_t frameIndex,
    const char* details
) {
    if (instance == nullptr || stage == nullptr || operation == nullptr) return;
    PendingRuntimeTelemetry event;
    event.eventName = "xr_trace";
    event.sessionId = sessionId;
    event.traceStage = stage;
    event.traceOperation = operation;
    event.traceResult = openXrTraceResultName(result);
    event.traceResultCode = static_cast<std::int32_t>(result);
    event.traceDurationNs = durationNs;
    event.traceFrameIndex = frameIndex;
    event.traceThreadId = static_cast<std::uint64_t>(
        std::hash<std::thread::id>{}(std::this_thread::get_id())) & 0x7fffffffffffffffULL;
    event.traceMonotonicNs = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());
    event.traceDetails = details == nullptr ? "" : details;
    {
        std::lock_guard<std::mutex> lock(instance->runtimeTelemetryMutex);
        auto& queue = instance->pendingRuntimeTelemetry;
        constexpr std::size_t kMaximumPendingRuntimeEvents = 128;
        if (queue.size() >= kMaximumPendingRuntimeEvents) {
            const auto expendable = std::find_if(
                queue.begin(), queue.end(),
                [](const PendingRuntimeTelemetry& queued) {
                    return queued.eventName == "xr_end_frame" ||
                        (queued.eventName == "xr_trace" && queued.traceResultCode == 0);
                });
            if (expendable != queue.end()) {
                queue.erase(expendable);
            } else if (result == XR_SUCCESS) {
                return;
            } else {
                queue.pop_front();
            }
        }
        queue.push_back(std::move(event));
    }
    instance->externalInputWake.notify_one();
}

bool flushRuntimeTelemetryImpl(XrInstance_T* instance) {
    if (instance == nullptr || instance->applicationVm == nullptr ||
        instance->applicationActivity == nullptr) return false;

    std::deque<PendingRuntimeTelemetry> pending;
    {
        std::lock_guard<std::mutex> lock(instance->runtimeTelemetryMutex);
        if (instance->pendingRuntimeTelemetry.empty()) return true;
        const auto now = std::chrono::steady_clock::now();
        if (instance->runtimeTelemetryRetryAt.time_since_epoch().count() != 0 &&
            now < instance->runtimeTelemetryRetryAt) {
            return false;
        }
        pending.swap(instance->pendingRuntimeTelemetry);
    }
    auto restorePending = [&]() {
        std::lock_guard<std::mutex> lock(instance->runtimeTelemetryMutex);
        for (auto it = pending.rbegin(); it != pending.rend(); ++it) {
            instance->pendingRuntimeTelemetry.push_front(*it);
        }
        // Keep the queue bounded if the provider remains unavailable for a
        // long time. Control/lifecycle events are enqueued before frame events.
        while (instance->pendingRuntimeTelemetry.size() > 128) {
            instance->pendingRuntimeTelemetry.pop_back();
        }
        instance->runtimeTelemetryRetryAt =
            std::chrono::steady_clock::now() + std::chrono::seconds(1);
    };

    JNIEnv* env = nullptr;
    bool attachedHere = false;
    const jint envStatus = instance->applicationVm->GetEnv(
        reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (envStatus == JNI_EDETACHED) {
        if (instance->applicationVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            restorePending();
            return false;
        }
        attachedHere = true;
    } else if (envStatus != JNI_OK || env == nullptr) {
        restorePending();
        return false;
    }

    bool success = false;
    const jint localFrameStatus = env->PushLocalFrame(32);
    if (localFrameStatus >= 0) {
        do {
            jclass contextClass = env->FindClass("android/content/Context");
            if (contextClass == nullptr || env->ExceptionCheck()) break;
            jmethodID getContentResolver = env->GetMethodID(
                contextClass,
                "getContentResolver",
                "()Landroid/content/ContentResolver;"
            );
            if (getContentResolver == nullptr || env->ExceptionCheck()) break;
            jobject resolver = env->CallObjectMethod(
                instance->applicationActivity,
                getContentResolver
            );
            if (resolver == nullptr || env->ExceptionCheck()) break;

            jclass uriClass = env->FindClass("android/net/Uri");
            if (uriClass == nullptr || env->ExceptionCheck()) break;
            jmethodID parseUri = env->GetStaticMethodID(
                uriClass,
                "parse",
                "(Ljava/lang/String;)Landroid/net/Uri;"
            );
            if (parseUri == nullptr || env->ExceptionCheck()) break;
            jstring uriText = env->NewStringUTF(kRuntimeTelemetryUri);
            if (uriText == nullptr || env->ExceptionCheck()) break;
            jobject uri = env->CallStaticObjectMethod(uriClass, parseUri, uriText);
            if (uri == nullptr || env->ExceptionCheck()) break;

            jclass valuesClass = env->FindClass("android/content/ContentValues");
            if (valuesClass == nullptr || env->ExceptionCheck()) break;
            jmethodID valuesConstructor = env->GetMethodID(valuesClass, "<init>", "()V");
            jmethodID putString = env->GetMethodID(
                valuesClass,
                "put",
                "(Ljava/lang/String;Ljava/lang/String;)V"
            );
            if (valuesConstructor == nullptr || putString == nullptr ||
                env->ExceptionCheck()) break;
            jclass longClass = env->FindClass("java/lang/Long");
            if (longClass == nullptr || env->ExceptionCheck()) break;
            jmethodID longValueOf = env->GetStaticMethodID(
                longClass,
                "valueOf",
                "(J)Ljava/lang/Long;"
            );
            jmethodID putLong = env->GetMethodID(
                valuesClass,
                "put",
                "(Ljava/lang/String;Ljava/lang/Long;)V"
            );
            if (longValueOf == nullptr || putLong == nullptr ||
                env->ExceptionCheck()) break;

            jclass resolverClass = env->FindClass("android/content/ContentResolver");
            if (resolverClass == nullptr || env->ExceptionCheck()) break;
            jmethodID insert = env->GetMethodID(
                resolverClass,
                "insert",
                "(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;"
            );
            if (insert == nullptr || env->ExceptionCheck()) break;

            success = true;
            for (const PendingRuntimeTelemetry& event : pending) {
                if (env->PushLocalFrame(16) < 0) {
                    success = false;
                    break;
                }
                jobject values = env->NewObject(valuesClass, valuesConstructor);
                auto putText = [&](const char* key, const std::string& value) -> bool {
                    jstring javaKey = env->NewStringUTF(key);
                    jstring javaValue = env->NewStringUTF(value.c_str());
                    if (javaKey == nullptr || javaValue == nullptr || env->ExceptionCheck()) {
                        return false;
                    }
                    env->CallVoidMethod(values, putString, javaKey, javaValue);
                    return !env->ExceptionCheck();
                };
                auto putNumber = [&](const char* key, std::uint64_t value) -> bool {
                    jstring javaKey = env->NewStringUTF(key);
                    jobject javaValue = env->CallStaticObjectMethod(
                        longClass,
                        longValueOf,
                        static_cast<jlong>(value)
                    );
                    if (javaKey == nullptr || javaValue == nullptr || env->ExceptionCheck()) {
                        return false;
                    }
                    env->CallVoidMethod(values, putLong, javaKey, javaValue);
                    return !env->ExceptionCheck();
                };

                bool itemOk = values != nullptr &&
                    putText("event", event.eventName) &&
                    putText("instance_id", std::to_string(instance->telemetryId)) &&
                    putNumber("frame_count", event.frameCount);
                if (itemOk && event.sessionId != 0) {
                    itemOk = putText("session_id", std::to_string(event.sessionId));
                }
                if (itemOk && event.eventName == "xr_trace") {
                    itemOk = putText("trace_stage", event.traceStage) &&
                        putText("trace_operation", event.traceOperation) &&
                        putText("trace_result", event.traceResult) &&
                        putNumber("trace_result_code",
                            static_cast<std::uint64_t>(static_cast<std::int64_t>(event.traceResultCode))) &&
                        putNumber("trace_duration_ns", event.traceDurationNs) &&
                        putNumber("trace_frame_index", event.traceFrameIndex) &&
                        putNumber("trace_thread_id", event.traceThreadId) &&
                        putNumber("trace_monotonic_ns", event.traceMonotonicNs) &&
                        putText("trace_details", event.traceDetails);
                }
                jobject inserted = nullptr;
                if (itemOk) {
                    inserted = env->CallObjectMethod(resolver, insert, uri, values);
                    itemOk = inserted != nullptr && !env->ExceptionCheck();
                }
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->PopLocalFrame(nullptr);
                if (!itemOk) {
                    success = false;
                    break;
                }
            }
        } while (false);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            success = false;
        }
        env->PopLocalFrame(nullptr);
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (attachedHere) instance->applicationVm->DetachCurrentThread();

    if (!success) {
        restorePending();
    } else {
        std::lock_guard<std::mutex> lock(instance->runtimeTelemetryMutex);
        instance->runtimeTelemetryRetryAt = {};
    }

    if (!success &&
        !instance->runtimeTelemetryUnavailableLogged.exchange(true, std::memory_order_acq_rel)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "EndXR",
            "Runtime telemetry unavailable; verify the package-scoped runtime_event URI grant."
        );
    } else if (success) {
        instance->runtimeTelemetryUnavailableLogged.store(false, std::memory_order_release);
    }
    return success;
}

bool startExternalControllerInputPolling(XrInstance_T* instance) {
    if (instance == nullptr || !instance->androidExtensionEnabled ||
        instance->applicationVm == nullptr || instance->applicationActivity == nullptr ||
        instance->externalInputPollThread.joinable()) return false;

    instance->stopExternalInputPollingRequested.store(false, std::memory_order_release);
    try {
        instance->externalInputPollThread = std::thread([instance]() {
            for (;;) {
                const bool stopping = instance->stopExternalInputPollingRequested.load(
                    std::memory_order_acquire
                );
                if (!stopping) {
                    bool inputSnapshotAvailable = false;
                    const auto inputStartedAt = std::chrono::steady_clock::now();
                    try {
                        inputSnapshotAvailable = refreshExternalControllerInputImpl(instance);
                    } catch (...) {
                        // A failed snapshot must not terminate the game process.
                        endxrClearControllerInput();
                        endxrClearHeadOrientation();
                        endxrClearHandPoses();
                    }
                    const std::uint64_t pollCount =
                        instance->externalInputPollCount.fetch_add(1, std::memory_order_relaxed) + 1;
                    if (pollCount % 50 == 0) {
                        const auto inputDuration = std::chrono::duration_cast<std::chrono::nanoseconds>(
                            std::chrono::steady_clock::now() - inputStartedAt).count();
                        enqueueRuntimeTrace(
                            instance, 0, "INPUT_IPC", "xrInputSnapshot",
                            inputSnapshotAvailable ? XR_SUCCESS : XR_ERROR_RUNTIME_FAILURE,
                            static_cast<std::uint64_t>(std::max<std::int64_t>(0, inputDuration)),
                            0,
                            inputSnapshotAvailable
                                ? "polls=50;result=provider_snapshot_read"
                                : "polls=50;result=provider_snapshot_unavailable_or_stale"
                        );
                    }
                }
                try {
                    flushRuntimeTelemetryImpl(instance);
                } catch (...) {
                    // Telemetry is diagnostic-only and must never kill the app.
                }
                if (stopping) break;

                std::unique_lock<std::mutex> waitLock(instance->externalInputWakeMutex);
                instance->externalInputWake.wait_for(
                    waitLock,
                    kExternalInputPollInterval,
                    [instance]() {
                        return instance->stopExternalInputPollingRequested.load(
                            std::memory_order_acquire);
                    }
                );
            }
        });
    } catch (...) {
        instance->stopExternalInputPollingRequested.store(true, std::memory_order_release);
        return false;
    }
    return true;
}

void stopExternalControllerInputPolling(XrInstance_T* instance) {
    if (instance == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(instance->runtimeTelemetryMutex);
        instance->runtimeTelemetryRetryAt = {};
    }
    instance->stopExternalInputPollingRequested.store(true, std::memory_order_release);
    instance->externalInputWake.notify_all();
    if (instance->externalInputPollThread.joinable()) {
        instance->externalInputPollThread.join();
    }
    // The worker may have encountered a transient provider failure on its
    // final pass. Retry once synchronously before the Activity global ref and
    // runtime instance are released.
    {
        std::lock_guard<std::mutex> lock(instance->runtimeTelemetryMutex);
        instance->runtimeTelemetryRetryAt = {};
    }
    flushRuntimeTelemetryImpl(instance);
}
#endif
bool locateEstimatedHandPose(XrSpace_T* object, XrSpace_T* base, XrPosef* pose) {
    return locateEstimatedHandPoseImpl(object, base, pose);
}

XRAPI_ATTR XrResult XRAPI_CALL endxrStringToPath(
    XrInstance instance, const char* pathString, XrPath* path
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (pathString == nullptr || path == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *path = XR_NULL_PATH;
    const std::size_t length = ::strnlen(pathString, XR_MAX_PATH_LENGTH);
    if (length >= XR_MAX_PATH_LENGTH) return XR_ERROR_PATH_FORMAT_INVALID;
    const std::string value(pathString, length);
    if (!validOpenXrPath(value)) return XR_ERROR_PATH_FORMAT_INVALID;
    const auto existing = owner->pathHandles.find(value);
    if (existing != owner->pathHandles.end()) {
        *path = existing->second;
        return XR_SUCCESS;
    }
    const XrPath created = owner->nextPath++;
    if (created == XR_NULL_PATH) return XR_ERROR_LIMIT_REACHED;
    owner->pathHandles.emplace(value, created);
    owner->pathStrings.emplace(created, value);
    *path = created;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrPathToString(
    XrInstance instance, XrPath path, std::uint32_t capacity,
    std::uint32_t* count, char* buffer
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const auto found = owner->pathStrings.find(path);
    if (found == owner->pathStrings.end()) return XR_ERROR_PATH_INVALID;
    const std::uint32_t required = static_cast<std::uint32_t>(found->second.size() + 1);
    *count = required;
    if (capacity == 0) return XR_SUCCESS;
    if (buffer == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < required) return XR_ERROR_SIZE_INSUFFICIENT;
    std::memcpy(buffer, found->second.c_str(), required);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateActionSet(
    XrInstance instance, const XrActionSetCreateInfo* createInfo, XrActionSet* actionSet
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || actionSet == nullptr ||
        createInfo->type != XR_TYPE_ACTION_SET_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *actionSet = XR_NULL_HANDLE;
    std::string name;
    std::string localizedName;
    if (!readFixedName(createInfo->actionSetName, sizeof(createInfo->actionSetName), &name) ||
        !readFixedName(createInfo->localizedActionSetName,
                       sizeof(createInfo->localizedActionSetName), &localizedName)) {
        return XR_ERROR_NAME_INVALID;
    }
    if (!validActionIdentifier(name)) return XR_ERROR_NAME_INVALID;

    auto* created = new (std::nothrow) XrActionSet_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = instance;
    created->name = name;
    created->localizedName = localizedName;
    created->priority = createInfo->priority;
    {
        std::lock_guard<std::mutex> lock(g_actionMutex);
        for (const auto& entry : g_actionSets) {
            if (entry.second->owner == instance && entry.second->name == name) {
                delete created;
                return XR_ERROR_NAME_DUPLICATED;
            }
        }
        *actionSet = makeActionSetHandle(created);
        g_actionSets.emplace(*actionSet, created);
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroyActionSet(XrActionSet actionSet) {
    XrActionSet_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_actionMutex);
        const auto found = g_actionSets.find(actionSet);
        if (found == g_actionSets.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        if (!object->actions.empty() || object->attachedSession != XR_NULL_HANDLE) {
            return XR_ERROR_CALL_ORDER_INVALID;
        }
        g_actionSets.erase(found);
    }
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateAction(
    XrActionSet actionSet, const XrActionCreateInfo* createInfo, XrAction* action
) {
    XrActionSet_T* owner = getActionSet(actionSet);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || action == nullptr ||
        createInfo->type != XR_TYPE_ACTION_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *action = XR_NULL_HANDLE;
    if (owner->attachedSession != XR_NULL_HANDLE) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;

    std::string name;
    std::string localizedName;
    if (!readFixedName(createInfo->actionName, sizeof(createInfo->actionName), &name) ||
        !readFixedName(createInfo->localizedActionName,
                       sizeof(createInfo->localizedActionName), &localizedName)) {
        return XR_ERROR_NAME_INVALID;
    }
    if (!validActionIdentifier(name)) return XR_ERROR_NAME_INVALID;
    switch (createInfo->actionType) {
        case XR_ACTION_TYPE_BOOLEAN_INPUT:
        case XR_ACTION_TYPE_FLOAT_INPUT:
        case XR_ACTION_TYPE_VECTOR2F_INPUT:
        case XR_ACTION_TYPE_POSE_INPUT:
        case XR_ACTION_TYPE_VIBRATION_OUTPUT:
            break;
        default:
            return XR_ERROR_ACTION_TYPE_MISMATCH;
    }
    if (createInfo->countSubactionPaths > 0 && createInfo->subactionPaths == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }

    auto* created = new (std::nothrow) XrAction_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = actionSet;
    created->name = name;
    created->localizedName = localizedName;
    created->type = createInfo->actionType;
    created->subactionPaths.reserve(createInfo->countSubactionPaths);
    for (std::uint32_t index = 0; index < createInfo->countSubactionPaths; ++index) {
        const XrPath path = createInfo->subactionPaths[index];
        if (!validUserPath(owner->owner, path)) {
            delete created;
            return XR_ERROR_PATH_UNSUPPORTED;
        }
        if (std::find(created->subactionPaths.begin(), created->subactionPaths.end(), path) !=
            created->subactionPaths.end()) {
            delete created;
            return XR_ERROR_PATH_UNSUPPORTED;
        }
        created->subactionPaths.push_back(path);
    }

    {
        std::lock_guard<std::mutex> lock(g_actionMutex);
        const auto currentSet = g_actionSets.find(actionSet);
        if (currentSet == g_actionSets.end()) {
            delete created;
            return XR_ERROR_HANDLE_INVALID;
        }
        if (currentSet->second->attachedSession != XR_NULL_HANDLE) {
            delete created;
            return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
        }
        for (XrAction existing : currentSet->second->actions) {
            const auto found = g_actions.find(existing);
            if (found != g_actions.end() && found->second->name == name) {
                delete created;
                return XR_ERROR_NAME_DUPLICATED;
            }
        }
        *action = makeActionHandle(created);
        g_actions.emplace(*action, created);
        currentSet->second->actions.insert(*action);
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroyAction(XrAction action) {
    XrAction_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_actionMutex);
        const auto found = g_actions.find(action);
        if (found == g_actions.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        if (!object->spaces.empty()) return XR_ERROR_CALL_ORDER_INVALID;
        const auto owner = g_actionSets.find(object->owner);
        if (owner != g_actionSets.end()) owner->second->actions.erase(action);
        g_actions.erase(found);
    }
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrSuggestInteractionProfileBindings(
    XrInstance instance, const XrInteractionProfileSuggestedBinding* suggestedBindings
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (suggestedBindings == nullptr ||
        suggestedBindings->type != XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    std::string profile;
    if (!pathBelongsToInstance(instance, suggestedBindings->interactionProfile, &profile)) {
        return XR_ERROR_PATH_INVALID;
    }
    if (profile.compare(0, 22, "/interaction_profiles/") != 0) {
        return XR_ERROR_PATH_UNSUPPORTED;
    }
    if (suggestedBindings->countSuggestedBindings > 0 &&
        suggestedBindings->suggestedBindings == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (profile == "/interaction_profiles/ext/hand_interaction_ext" &&
        !owner->handInteractionExtensionEnabled) {
        return XR_ERROR_PATH_UNSUPPORTED;
    }
    std::vector<XrActionSuggestedBinding> bindings;
    bindings.reserve(suggestedBindings->countSuggestedBindings);
    for (std::uint32_t index = 0; index < suggestedBindings->countSuggestedBindings; ++index) {
        const auto& binding = suggestedBindings->suggestedBindings[index];
        XrAction_T* action = getAction(binding.action);
        if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
        XrActionSet_T* actionSet = getActionSet(action->owner);
        if (actionSet == nullptr || actionSet->owner != instance) return XR_ERROR_HANDLE_INVALID;
        if (actionSet->attachedSession != XR_NULL_HANDLE) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
        if (!pathBelongsToInstance(instance, binding.binding)) return XR_ERROR_PATH_INVALID;
        if (std::any_of(bindings.begin(), bindings.end(), [&](const auto& item) {
                return item.action == binding.action && item.binding == binding.binding;
            })) {
            return XR_ERROR_PATH_UNSUPPORTED;
        }
        bindings.push_back(binding);
    }
    owner->suggestedBindings[suggestedBindings->interactionProfile] = std::move(bindings);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrAttachSessionActionSets(
    XrSession session, const XrSessionActionSetsAttachInfo* attachInfo
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (attachInfo == nullptr || attachInfo->type != XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (owner->actionSetsAttached) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
    if (attachInfo->countActionSets == 0 || attachInfo->actionSets == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    std::unordered_set<XrActionSet> unique;
    std::lock_guard<std::mutex> lock(g_actionMutex);
    for (std::uint32_t index = 0; index < attachInfo->countActionSets; ++index) {
        const XrActionSet handle = attachInfo->actionSets[index];
        const auto found = g_actionSets.find(handle);
        if (found == g_actionSets.end()) return XR_ERROR_HANDLE_INVALID;
        if (found->second->owner != owner->owner) return XR_ERROR_HANDLE_INVALID;
        if (found->second->attachedSession != XR_NULL_HANDLE) {
            return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
        }
        if (!unique.insert(handle).second) return XR_ERROR_VALIDATION_FAILURE;
    }
    for (XrActionSet handle : unique) {
        g_actionSets.at(handle)->attachedSession = session;
        owner->attachedActionSets.insert(handle);
    }
    owner->actionSetsAttached = true;
    owner->actionSyncCompleted = false;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetCurrentInteractionProfile(
    XrSession session, XrPath topLevelUserPath, XrInteractionProfileState* interactionProfile
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (interactionProfile == nullptr ||
        interactionProfile->type != XR_TYPE_INTERACTION_PROFILE_STATE) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!validUserPath(owner->owner, topLevelUserPath)) return XR_ERROR_PATH_UNSUPPORTED;
    std::string userPath;
    if (!pathBelongsToInstance(owner->owner, topLevelUserPath, &userPath)) return XR_ERROR_PATH_UNSUPPORTED;
    const int hand = handIndexFromPath(userPath);
    interactionProfile->interactionProfile =
        hand < 0 ? XR_NULL_PATH : currentProfileForHand(owner, hand);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrSyncActions(
    XrSession session, const XrActionsSyncInfo* syncInfo
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (syncInfo == nullptr || syncInfo->type != XR_TYPE_ACTIONS_SYNC_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!owner->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (owner->state != XR_SESSION_STATE_FOCUSED) return XR_SESSION_NOT_FOCUSED;
    if (!owner->actionSetsAttached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    if (syncInfo->countActiveActionSets > 0 && syncInfo->activeActionSets == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }

    std::vector<XrActiveActionSet> active;
    active.reserve(syncInfo->countActiveActionSets);
    std::unordered_set<XrActionSet> unique;
    for (std::uint32_t index = 0; index < syncInfo->countActiveActionSets; ++index) {
        const auto& item = syncInfo->activeActionSets[index];
        XrActionSet_T* actionSet = getActionSet(item.actionSet);
        if (actionSet == nullptr) return XR_ERROR_HANDLE_INVALID;
        if (actionSet->attachedSession != session ||
            owner->attachedActionSets.find(item.actionSet) == owner->attachedActionSets.end()) {
            return XR_ERROR_ACTIONSET_NOT_ATTACHED;
        }
        if (!unique.insert(item.actionSet).second) return XR_ERROR_VALIDATION_FAILURE;
        if (item.subactionPath != XR_NULL_PATH &&
            !validUserPath(owner->owner, item.subactionPath)) return XR_ERROR_PATH_INVALID;
        active.push_back(item);
    }
    // Input is refreshed by the instance-owned IPC worker. Synchronize only
    // the most recent validated sample here; never issue Binder work on the
    // application's action/input thread.

    owner->activeActionSets = std::move(active);
    for (int hand = 0; hand < 2; ++hand) {
        const EndXrControllerInput input = readControllerInput(hand);
        auto& synchronized = owner->synchronizedControllerInputs[static_cast<std::size_t>(hand)];
        synchronized.active = input.active;
        synchronized.trigger = input.trigger;
        synchronized.grip = input.grip;
        synchronized.stickX = input.stickX;
        synchronized.stickY = input.stickY;
        synchronized.buttons = input.buttons;
        synchronized.poseActive = input.poseActive;
        synchronized.gripPose = input.gripPose;
        synchronized.handJointsActive = input.handJointsActive;
        synchronized.handJoints = input.handJoints;
    }
    rebuildActionStateSnapshots(owner);
    owner->actionSyncCompleted = true;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetActionStateBoolean(
    XrSession session, const XrActionStateGetInfo* getInfo, XrActionStateBoolean* state
) {
    XrSession_T* owner = getSession(session);
    if (state == nullptr || state->type != XR_TYPE_ACTION_STATE_BOOLEAN) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = nullptr;
    XrResult result = validateActionForSession(
        owner, getInfo, XR_ACTION_TYPE_BOOLEAN_INPUT, &action
    );
    if (XR_FAILED(result)) return result;
    const auto input = getActionStateSnapshot(owner, getInfo->action, getInfo->subactionPath);
    state->currentState = input.booleanValue ? XR_TRUE : XR_FALSE;
    state->changedSinceLastSync = input.booleanChanged ? XR_TRUE : XR_FALSE;
    state->lastChangeTime = input.booleanLastChangeTime;
    state->isActive = input.active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetActionStateFloat(
    XrSession session, const XrActionStateGetInfo* getInfo, XrActionStateFloat* state
) {
    XrSession_T* owner = getSession(session);
    if (state == nullptr || state->type != XR_TYPE_ACTION_STATE_FLOAT) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = nullptr;
    XrResult result = validateActionForSession(
        owner, getInfo, XR_ACTION_TYPE_FLOAT_INPUT, &action
    );
    if (XR_FAILED(result)) return result;
    const auto input = getActionStateSnapshot(owner, getInfo->action, getInfo->subactionPath);
    state->currentState = input.scalarValue;
    state->changedSinceLastSync = input.scalarChanged ? XR_TRUE : XR_FALSE;
    state->lastChangeTime = input.scalarLastChangeTime;
    state->isActive = input.active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetActionStateVector2f(
    XrSession session, const XrActionStateGetInfo* getInfo, XrActionStateVector2f* state
) {
    XrSession_T* owner = getSession(session);
    if (state == nullptr || state->type != XR_TYPE_ACTION_STATE_VECTOR2F) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = nullptr;
    XrResult result = validateActionForSession(
        owner, getInfo, XR_ACTION_TYPE_VECTOR2F_INPUT, &action
    );
    if (XR_FAILED(result)) return result;
    const auto input = getActionStateSnapshot(owner, getInfo->action, getInfo->subactionPath);
    state->currentState = input.vectorValue;
    state->changedSinceLastSync = input.vectorChanged ? XR_TRUE : XR_FALSE;
    state->lastChangeTime = input.vectorLastChangeTime;
    state->isActive = input.active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetActionStatePose(
    XrSession session, const XrActionStateGetInfo* getInfo, XrActionStatePose* state
) {
    XrSession_T* owner = getSession(session);
    if (state == nullptr || state->type != XR_TYPE_ACTION_STATE_POSE) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = nullptr;
    XrResult result = validateActionForSession(
        owner, getInfo, XR_ACTION_TYPE_POSE_INPUT, &action
    );
    if (XR_FAILED(result)) return result;
    state->isActive = resolveActionPose(owner, action, getInfo->subactionPath).active
        ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateActionSpace(
    XrSession session, const XrActionSpaceCreateInfo* createInfo, XrSpace* space
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || space == nullptr ||
        createInfo->type != XR_TYPE_ACTION_SPACE_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *space = XR_NULL_HANDLE;
    XrAction_T* action = getAction(createInfo->action);
    if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (action->type != XR_ACTION_TYPE_POSE_INPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    XrActionSet_T* actionSet = getActionSet(action->owner);
    if (actionSet == nullptr || actionSet->attachedSession != session ||
        owner->attachedActionSets.find(action->owner) == owner->attachedActionSets.end()) {
        return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    }
    if (!validActionSubactionPath(action, createInfo->subactionPath)) {
        return XR_ERROR_PATH_INVALID;
    }
    auto* created = new (std::nothrow) XrSpace_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = session;
    created->isActionSpace = true;
    created->action = createInfo->action;
    created->actionSubactionPath = createInfo->subactionPath;
    created->poseInReferenceSpace = createInfo->poseInActionSpace;
    const XrSpace handle = makeSpaceHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_spaceMutex);
        g_spaces.emplace(handle, created);
    }
    owner->spaces.insert(handle);
    {
        std::lock_guard<std::mutex> lock(g_actionMutex);
        const auto found = g_actions.find(createInfo->action);
        if (found != g_actions.end()) found->second->spaces.insert(handle);
    }
    *space = handle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateBoundSourcesForAction(
    XrSession session, const XrBoundSourcesForActionEnumerateInfo* enumerateInfo,
    std::uint32_t capacity, std::uint32_t* count, XrPath* sources
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (enumerateInfo == nullptr ||
        enumerateInfo->type != XR_TYPE_BOUND_SOURCES_FOR_ACTION_ENUMERATE_INFO ||
        count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    XrAction_T* action = getAction(enumerateInfo->action);
    if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrActionSet_T* actionSet = getActionSet(action->owner);
    if (actionSet == nullptr || actionSet->attachedSession != session) {
        return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    }
    *count = 0;
    if (capacity > 0 && sources == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    XrInstance_T* instance = getInstance(owner->owner);
    if (instance == nullptr) return XR_ERROR_HANDLE_INVALID;
    std::vector<XrPath> activeSources;
    for (int hand = 0; hand < 2; ++hand) {
        const XrPath profile = currentProfileForHand(owner, hand);
        const auto found = instance->suggestedBindings.find(profile);
        if (profile == XR_NULL_PATH || found == instance->suggestedBindings.end()) continue;
        const XrPath handPath = hand == 0
            ? (instance->pathHandles.count("/user/hand/left") ? instance->pathHandles.at("/user/hand/left") : XR_NULL_PATH)
            : (instance->pathHandles.count("/user/hand/right") ? instance->pathHandles.at("/user/hand/right") : XR_NULL_PATH);
        for (const auto& binding : found->second) {
            if (binding.action != enumerateInfo->action) continue;
            std::string path;
            if (pathBelongsToInstance(owner->owner, binding.binding, &path) &&
                handIndexFromPath(path) == hand && handPath != XR_NULL_PATH &&
                std::find(activeSources.begin(), activeSources.end(), binding.binding) == activeSources.end()) {
                activeSources.push_back(binding.binding);
            }
        }
    }
    *count = static_cast<std::uint32_t>(activeSources.size());
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < activeSources.size()) return XR_ERROR_SIZE_INSUFFICIENT;
    for (std::uint32_t i = 0; i < *count; ++i) sources[i] = activeSources[i];
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetInputSourceLocalizedName(
    XrSession session, const XrInputSourceLocalizedNameGetInfo* getInfo,
    std::uint32_t capacity, std::uint32_t* count, char* buffer
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (getInfo == nullptr ||
        getInfo->type != XR_TYPE_INPUT_SOURCE_LOCALIZED_NAME_GET_INFO ||
        count == nullptr || getInfo->whichComponents == 0 ||
        (getInfo->whichComponents & ~(XR_INPUT_SOURCE_LOCALIZED_NAME_USER_PATH_BIT |
                                     XR_INPUT_SOURCE_LOCALIZED_NAME_INTERACTION_PROFILE_BIT |
                                     XR_INPUT_SOURCE_LOCALIZED_NAME_COMPONENT_BIT)) != 0) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    std::string path;
    if (!pathBelongsToInstance(owner->owner, getInfo->sourcePath, &path)) {
        return XR_ERROR_PATH_INVALID;
    }
    const std::uint32_t required = static_cast<std::uint32_t>(path.size() + 1);
    *count = required;
    if (capacity == 0) return XR_SUCCESS;
    if (buffer == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < required) return XR_ERROR_SIZE_INSUFFICIENT;
    std::memcpy(buffer, path.c_str(), required);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrApplyHapticFeedback(
    XrSession session, const XrHapticActionInfo* actionInfo,
    const XrHapticBaseHeader* feedback
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (actionInfo == nullptr || actionInfo->type != XR_TYPE_HAPTIC_ACTION_INFO ||
        feedback == nullptr || feedback->type != XR_TYPE_HAPTIC_VIBRATION) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = getAction(actionInfo->action);
    if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (action->type != XR_ACTION_TYPE_VIBRATION_OUTPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    XrActionSet_T* actionSet = getActionSet(action->owner);
    if (actionSet == nullptr || actionSet->attachedSession != session) {
        return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    }
    if (!validActionSubactionPath(action, actionInfo->subactionPath)) return XR_ERROR_PATH_INVALID;
    return XR_ERROR_FEATURE_UNSUPPORTED;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrStopHapticFeedback(
    XrSession session, const XrHapticActionInfo* actionInfo
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (actionInfo == nullptr || actionInfo->type != XR_TYPE_HAPTIC_ACTION_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = getAction(actionInfo->action);
    if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (action->type != XR_ACTION_TYPE_VIBRATION_OUTPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    XrActionSet_T* actionSet = getActionSet(action->owner);
    if (actionSet == nullptr || actionSet->attachedSession != session) {
        return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    }
    if (!validActionSubactionPath(action, actionInfo->subactionPath)) return XR_ERROR_PATH_INVALID;
    return XR_ERROR_FEATURE_UNSUPPORTED;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateHandTrackerEXT(
    XrSession session, const XrHandTrackerCreateInfoEXT* createInfo,
    XrHandTrackerEXT* handTracker
) {
    if (handTracker != nullptr) *handTracker = XR_NULL_HANDLE;
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrInstance_T* instance = getInstance(owner->owner);
    if (instance == nullptr || !instance->handTrackingExtensionEnabled) {
        return XR_ERROR_FUNCTION_UNSUPPORTED;
    }
    if (createInfo == nullptr || handTracker == nullptr ||
        createInfo->type != XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (createInfo->next != nullptr ||
        (createInfo->hand != XR_HAND_LEFT_EXT && createInfo->hand != XR_HAND_RIGHT_EXT) ||
        createInfo->handJointSet != XR_HAND_JOINT_SET_DEFAULT_EXT) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    auto* created = new (std::nothrow) XrHandTracker_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = session;
    created->hand = createInfo->hand;
    const XrHandTrackerEXT handle = makeHandTrackerHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_handTrackerMutex);
        g_handTrackers.emplace(handle, created);
    }
    owner->handTrackers.insert(handle);
    *handTracker = handle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroyHandTrackerEXT(XrHandTrackerEXT handTracker) {
    XrHandTracker_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_handTrackerMutex);
        const auto found = g_handTrackers.find(handTracker);
        if (found == g_handTrackers.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        g_handTrackers.erase(found);
    }
    if (XrSession_T* owner = getSession(object->owner)) {
        owner->handTrackers.erase(handTracker);
    }
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrLocateHandJointsEXT(
    XrHandTrackerEXT handTracker,
    const XrHandJointsLocateInfoEXT* locateInfo,
    XrHandJointLocationsEXT* locations
) {
    XrHandTracker_T* tracker = getHandTracker(handTracker);
    if (tracker == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrSession_T* session = getSession(tracker->owner);
    if (session == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrInstance_T* instance = getInstance(session->owner);
    if (instance == nullptr || !instance->handTrackingExtensionEnabled) {
        return XR_ERROR_FUNCTION_UNSUPPORTED;
    }
    if (locateInfo == nullptr || locateInfo->type != XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT ||
        locations == nullptr || locations->type != XR_TYPE_HAND_JOINT_LOCATIONS_EXT) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    // XR_HAND_JOINT_VELOCITIES_EXT is an optional output structure chained
    // from XrHandJointLocationsEXT. We expose the structure but deliberately
    // leave velocity flags unset until temporal joint-velocity estimation exists.
    XrHandJointVelocitiesEXT* velocityOutput = nullptr;
    auto* outputChain = static_cast<BaseInStructure*>(locations->next);
    while (outputChain != nullptr) {
        if (outputChain->type == XR_TYPE_HAND_JOINT_VELOCITIES_EXT) {
            if (velocityOutput != nullptr) return XR_ERROR_VALIDATION_FAILURE;
            velocityOutput = reinterpret_cast<XrHandJointVelocitiesEXT*>(outputChain);
        }
        outputChain = const_cast<BaseInStructure*>(
            static_cast<const BaseInStructure*>(outputChain->next));
    }
    if (velocityOutput != nullptr) {
        if (velocityOutput->jointCount != XR_HAND_JOINT_COUNT_EXT ||
            velocityOutput->jointVelocities == nullptr) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
        for (std::uint32_t index = 0; index < XR_HAND_JOINT_COUNT_EXT; ++index) {
            auto& velocity = velocityOutput->jointVelocities[index];
            velocity.velocityFlags = 0;
            velocity.linearVelocity = {0.0f, 0.0f, 0.0f};
            velocity.angularVelocity = {0.0f, 0.0f, 0.0f};
        }
    }
    if (locateInfo->next != nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (locateInfo->time <= 0) return XR_ERROR_TIME_INVALID;
    if (locations->jointCount != XR_HAND_JOINT_COUNT_EXT ||
        locations->jointLocations == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrSpace_T* base = getSpace(locateInfo->baseSpace);
    if (base == nullptr || base->owner != tracker->owner || base->isActionSpace) {
        return XR_ERROR_HANDLE_INVALID;
    }
    const bool isFloorBase =
        base->type == XR_REFERENCE_SPACE_TYPE_STAGE ||
        base->type == XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR;
    const bool worldSpaceBase = base->type == XR_REFERENCE_SPACE_TYPE_LOCAL || isFloorBase;
    if (base->type != XR_REFERENCE_SPACE_TYPE_VIEW && !worldSpaceBase) {
        return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    }

    const int handIndex = tracker->hand == XR_HAND_LEFT_EXT ? 0 : 1;
    const EndXrHandTrackingSample sample = readFreshHandTrackingInput(handIndex);
    XrQuaternionf headOrientation{};
    if (sample.active && worldSpaceBase &&
        !readFreshHeadOrientation(&headOrientation)) {
        locations->isActive = XR_FALSE;
    } else {
        locations->isActive = sample.active ? XR_TRUE : XR_FALSE;
    }

    for (std::uint32_t index = 0; index < XR_HAND_JOINT_COUNT_EXT; ++index) {
        XrHandJointLocationEXT& output = locations->jointLocations[index];
        output.locationFlags = 0;
        output.pose.orientation = {0.0f, 0.0f, 0.0f, 1.0f};
        output.pose.position = {0.0f, 0.0f, 0.0f};
        output.radius = 0.0f;
        if (locations->isActive != XR_TRUE) continue;

        const std::size_t offset = static_cast<std::size_t>(index) * 8u;
        XrPosef localJointPose{};
        localJointPose.position = {
            sample.joints[offset], sample.joints[offset + 1], sample.joints[offset + 2]
        };
        localJointPose.orientation = normalizedQuaternion({
            sample.joints[offset + 4], sample.joints[offset + 5],
            sample.joints[offset + 6], sample.joints[offset + 7]
        });
        XrPosef locatedPose = composePose(sample.palmPose, localJointPose);
        if (worldSpaceBase) {
            XrPosef headPose{};
            headPose.orientation = headOrientation;
            headPose.position = {
                0.0f,
                isFloorBase ? kDefaultStageEyeHeightMeters : 0.0f,
                0.0f
            };
            locatedPose = composePose(headPose, locatedPose);
        }
        locatedPose = composePose(inversePose(base->poseInReferenceSpace), locatedPose);
        output.pose = locatedPose;
        output.radius = sample.joints[offset + 3];
        // MediaPipe positions are useful, but the phone camera does not provide
        // independently tracked metric 6DoF joint poses.
        output.locationFlags =
            XR_SPACE_LOCATION_POSITION_VALID_BIT |
            XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    }
    return XR_SUCCESS;
}



/*
 * Low-overhead OpenXR call tracing. Successful lifecycle/setup operations and
 * sampled compositor/input checkpoints are persisted through the consent-
 * protected telemetry worker. Every failed call is retained.
 */
std::mutex g_loaderTraceMutex;
bool g_loaderTracePending = false;
XrResult g_loaderTraceResult = XR_SUCCESS;
std::uint64_t g_loaderTraceDurationNs = 0;
std::string g_loaderTraceDetails;

#if defined(__ANDROID__)
const char* openXrTraceResultName(XrResult result) {
    switch (result) {
        case XR_SUCCESS: return "XR_SUCCESS";
        case XR_ERROR_VALIDATION_FAILURE: return "XR_ERROR_VALIDATION_FAILURE";
        case XR_ERROR_FUNCTION_UNSUPPORTED: return "XR_ERROR_FUNCTION_UNSUPPORTED";
        case XR_ERROR_EXTENSION_NOT_PRESENT: return "XR_ERROR_EXTENSION_NOT_PRESENT";
        case XR_ERROR_API_VERSION_UNSUPPORTED: return "XR_ERROR_API_VERSION_UNSUPPORTED";
        case XR_ERROR_INITIALIZATION_FAILED: return "XR_ERROR_INITIALIZATION_FAILED";
        case XR_ERROR_OUT_OF_MEMORY: return "XR_ERROR_OUT_OF_MEMORY";
        case XR_ERROR_HANDLE_INVALID: return "XR_ERROR_HANDLE_INVALID";
        case XR_ERROR_RUNTIME_FAILURE: return "XR_ERROR_RUNTIME_FAILURE";
        case XR_ERROR_CALL_ORDER_INVALID: return "XR_ERROR_CALL_ORDER_INVALID";
        case XR_ERROR_SESSION_NOT_RUNNING: return "XR_ERROR_SESSION_NOT_RUNNING";
        case XR_ERROR_GRAPHICS_DEVICE_INVALID: return "XR_ERROR_GRAPHICS_DEVICE_INVALID";
        case XR_ERROR_LAYER_INVALID: return "XR_ERROR_LAYER_INVALID";
        case XR_ERROR_TIME_INVALID: return "XR_ERROR_TIME_INVALID";
        case XR_ERROR_LIMIT_REACHED: return "XR_ERROR_LIMIT_REACHED";
        case XR_ERROR_SYSTEM_INVALID: return "XR_ERROR_SYSTEM_INVALID";
        case XR_ERROR_SESSION_NOT_READY: return "XR_ERROR_SESSION_NOT_READY";
        default: return result < 0 ? "XR_RESULT_ERROR" : "XR_RESULT_STATUS";
    }
}
#endif

std::uint64_t openXrTraceElapsedNs(std::chrono::steady_clock::time_point startedAt) {
    const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - startedAt).count();
    return static_cast<std::uint64_t>(std::max<std::int64_t>(0, elapsed));
}

void recordOpenXrTrace(
    XrInstance_T* instance,
    std::uint64_t sessionId,
    const char* stage,
    const char* operation,
    XrResult result,
    std::uint64_t durationNs,
    std::uint64_t frameIndex,
    const std::string& details,
    bool recordSuccess = true
) {
    if (result == XR_SUCCESS && !recordSuccess) return;
#if defined(__ANDROID__)
    const char* resultName = openXrTraceResultName(result);
    if (result < 0 || instance == nullptr) {
        __android_log_print(
            result < 0 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
            "EndXRTrace",
            "stage=%s operation=%s result=%s code=%d duration_ns=%llu frame=%llu details=%s",
            stage, operation, resultName, static_cast<int>(result),
            static_cast<unsigned long long>(durationNs),
            static_cast<unsigned long long>(frameIndex), details.c_str()
        );
    }
    if (instance != nullptr) {
        enqueueRuntimeTrace(
            instance, sessionId, stage, operation, result, durationNs,
            frameIndex, details.c_str());
    }
#else
    (void)instance;
    (void)sessionId;
    (void)stage;
    (void)operation;
    (void)result;
    (void)durationNs;
    (void)frameIndex;
    (void)details;
#endif
}

XrResult XRAPI_CALL endxrCreateInstance(
    const XrInstanceCreateInfo* createInfo, XrInstance* instance
) {
    const auto startedAt = std::chrono::steady_clock::now();
    std::string details = "requested_api=unknown;extensions=";
    if (createInfo != nullptr) {
        details = "requested_api=" +
            std::to_string(XR_VERSION_MAJOR(createInfo->applicationInfo.apiVersion)) + "." +
            std::to_string(XR_VERSION_MINOR(createInfo->applicationInfo.apiVersion)) + "." +
            std::to_string(XR_VERSION_PATCH(createInfo->applicationInfo.apiVersion)) +
            ";extension_count=" + std::to_string(createInfo->enabledExtensionCount) +
            ";extensions=";
        if (createInfo->enabledExtensionNames != nullptr) {
            const std::uint32_t count = std::min<std::uint32_t>(
                createInfo->enabledExtensionCount, 16);
            for (std::uint32_t index = 0; index < count; ++index) {
                if (index != 0) details += ",";
                const char* extension = createInfo->enabledExtensionNames[index];
                details += extension == nullptr ? "<null>" : extension;
                if (details.size() > 440) break;
            }
        }
    }
    if (details.size() > 480) details.resize(480);
    const XrResult result = endxrCreateInstanceImpl(createInfo, instance);
    XrInstance_T* owner = result == XR_SUCCESS && instance != nullptr
        ? getInstance(*instance) : nullptr;
    {
        std::lock_guard<std::mutex> lock(g_loaderTraceMutex);
        if (g_loaderTracePending && owner != nullptr) {
            recordOpenXrTrace(
                owner, 0, "LOADER", "xrNegotiateLoaderRuntimeInterface",
                g_loaderTraceResult, g_loaderTraceDurationNs, 0, g_loaderTraceDetails
            );
            g_loaderTracePending = false;
        }
    }
    recordOpenXrTrace(owner, 0, "INSTANCE", "xrCreateInstance", result,
        openXrTraceElapsedNs(startedAt), 0, details);
    return result;
}

XrResult XRAPI_CALL endxrGetSystem(
    XrInstance instance, const XrSystemGetInfo* getInfo, XrSystemId* systemId
) {
    XrInstance_T* owner = getInstance(instance);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrGetSystemImpl(instance, getInfo, systemId);
    const std::string details = "form_factor=" +
        std::to_string(getInfo == nullptr ? -1 : static_cast<int>(getInfo->formFactor)) +
        ";system_id=" + std::to_string(result == XR_SUCCESS && systemId != nullptr ? *systemId : 0);
    recordOpenXrTrace(owner, 0, "SYSTEM", "xrGetSystem", result,
        openXrTraceElapsedNs(startedAt), 0, details);
    return result;
}

XrResult XRAPI_CALL endxrGetOpenGLESGraphicsRequirementsKHR(
    XrInstance instance, XrSystemId systemId,
    XrGraphicsRequirementsOpenGLESKHR* requirements
) {
    XrInstance_T* owner = getInstance(instance);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result =
        endxrGetOpenGLESGraphicsRequirementsKHRImpl(instance, systemId, requirements);
    std::string details = "system_id=" + std::to_string(systemId);
    if (result == XR_SUCCESS && requirements != nullptr) {
        details += ";min_gles=" +
            std::to_string(XR_VERSION_MAJOR(requirements->minApiVersionSupported)) + "." +
            std::to_string(XR_VERSION_MINOR(requirements->minApiVersionSupported));
        details += ";max_gles=" +
            std::to_string(XR_VERSION_MAJOR(requirements->maxApiVersionSupported)) + "." +
            std::to_string(XR_VERSION_MINOR(requirements->maxApiVersionSupported));
    }
    recordOpenXrTrace(owner, 0, "GRAPHICS", "xrGetOpenGLESGraphicsRequirementsKHR",
        result, openXrTraceElapsedNs(startedAt), 0, details);
    return result;
}

XrResult XRAPI_CALL endxrCreateSession(
    XrInstance instance, const XrSessionCreateInfo* createInfo, XrSession* session
) {
    XrInstance_T* owner = getInstance(instance);
    const auto* binding = createInfo == nullptr ? nullptr : findGlesBinding(createInfo->next);
    bool currentContextMatches = binding != nullptr;
#if defined(__ANDROID__)
    currentContextMatches = binding != nullptr &&
        eglGetCurrentDisplay() == reinterpret_cast<EGLDisplay>(binding->display) &&
        eglGetCurrentContext() == reinterpret_cast<EGLContext>(binding->context);
#endif
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrCreateSessionImpl(instance, createInfo, session);
    XrSession_T* created = result == XR_SUCCESS && session != nullptr
        ? getSession(*session) : nullptr;
    const std::uint64_t sessionId = created == nullptr ? 0 : created->telemetryId;
    std::string details = "system_id=" +
        std::to_string(createInfo == nullptr ? 0 : createInfo->systemId) +
        ";egl_binding_present=" + (binding == nullptr ? "false" : "true") +
        ";egl_current_match=" + (currentContextMatches ? "true" : "false");
    if (binding != nullptr) details += ";display_config_context_non_null=" +
        std::string(binding->display != nullptr && binding->config != nullptr &&
            binding->context != nullptr ? "true" : "false");
    recordOpenXrTrace(owner, sessionId, "SESSION", "xrCreateSession", result,
        openXrTraceElapsedNs(startedAt), 0, details);
    return result;
}

XrResult XRAPI_CALL endxrBeginSession(
    XrSession session, const XrSessionBeginInfo* beginInfo
) {
    XrSession_T* object = getSession(session);
    XrInstance_T* owner = object == nullptr ? nullptr : getInstance(object->owner);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrBeginSessionImpl(session, beginInfo);
    recordOpenXrTrace(owner, object == nullptr ? 0 : object->telemetryId,
        "SESSION", "xrBeginSession", result, openXrTraceElapsedNs(startedAt), 0,
        "primary_view_configuration=" + std::to_string(
            beginInfo == nullptr ? -1 : static_cast<int>(beginInfo->primaryViewConfigurationType)));
    return result;
}

XrResult XRAPI_CALL endxrEndSession(XrSession session) {
    XrSession_T* object = getSession(session);
    XrInstance_T* owner = object == nullptr ? nullptr : getInstance(object->owner);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrEndSessionImpl(session);
    recordOpenXrTrace(owner, object == nullptr ? 0 : object->telemetryId,
        "SESSION", "xrEndSession", result, openXrTraceElapsedNs(startedAt), 0,
        "session_teardown=true", false);
    return result;
}

XrResult XRAPI_CALL endxrDestroySession(XrSession session) {
    XrSession_T* object = getSession(session);
    XrInstance_T* owner = object == nullptr ? nullptr : getInstance(object->owner);
    const std::uint64_t sessionId = object == nullptr ? 0 : object->telemetryId;
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrDestroySessionImpl(session);
    recordOpenXrTrace(owner, sessionId, "SESSION", "xrDestroySession", result,
        openXrTraceElapsedNs(startedAt), 0,
        std::string("session_destroyed=") + (result == XR_SUCCESS ? "true" : "false"),
        result != XR_SUCCESS);
    return result;
}

XrResult XRAPI_CALL endxrDestroyInstance(XrInstance instance) {
    XrInstance_T* owner = getInstance(instance);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrDestroyInstanceImpl(instance);
    // Successful destruction is traced inside the implementation before the
    // instance-owned telemetry worker and memory are released. The wrapper only
    // records errors here, while the original instance remains valid.
    if (result != XR_SUCCESS) {
        recordOpenXrTrace(owner, 0, "INSTANCE", "xrDestroyInstance", result,
            openXrTraceElapsedNs(startedAt), 0, "instance_destroyed=false");
    }
    return result;
}

XrResult XRAPI_CALL endxrWaitFrame(
    XrSession session, const XrFrameWaitInfo* waitInfo, XrFrameState* frameState
) {
    XrSession_T* object = getSession(session);
    XrInstance_T* owner = object == nullptr ? nullptr : getInstance(object->owner);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrWaitFrameImpl(session, waitInfo, frameState);
    std::uint64_t frameIndex = 0;
    if (object != nullptr && result == XR_SUCCESS) frameIndex = ++object->diagnosticFrameIndex;
    const bool sampleFrame = frameIndex > 0 && frameIndex % 30 == 0;
    if (result != XR_SUCCESS || sampleFrame) {
        std::string details = std::string("frame_state_valid=") +
            (frameState == nullptr ? "false" : "true");
        if (result == XR_SUCCESS && frameState != nullptr) {
            details += ";should_render=" +
                std::string(frameState->shouldRender == XR_TRUE ? "true" : "false") +
                ";predicted_display_period_ns=" +
                std::to_string(frameState->predictedDisplayPeriod);
        }
        recordOpenXrTrace(owner, object == nullptr ? 0 : object->telemetryId,
            "FRAME", "xrWaitFrame", result, openXrTraceElapsedNs(startedAt),
            frameIndex, details);
    }
    return result;
}

XrResult XRAPI_CALL endxrBeginFrame(
    XrSession session, const XrFrameBeginInfo* beginInfo
) {
    XrSession_T* object = getSession(session);
    XrInstance_T* owner = object == nullptr ? nullptr : getInstance(object->owner);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrBeginFrameImpl(session, beginInfo);
    const std::uint64_t frameIndex =
        object == nullptr ? 0 : object->diagnosticFrameIndex;
    if (result != XR_SUCCESS || (frameIndex > 0 && frameIndex % 30 == 0)) {
        recordOpenXrTrace(owner, object == nullptr ? 0 : object->telemetryId,
            "FRAME", "xrBeginFrame", result, openXrTraceElapsedNs(startedAt),
            frameIndex, std::string("frame_waited=") +
                (object != nullptr && object->frameWaited ? "true" : "false"));
    }
    return result;
}

XrResult XRAPI_CALL endxrEndFrame(
    XrSession session, const XrFrameEndInfo* endInfo
) {
    XrSession_T* object = getSession(session);
    XrInstance_T* owner = object == nullptr ? nullptr : getInstance(object->owner);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrEndFrameImpl(session, endInfo);
    const std::uint64_t compositedFrames =
        object == nullptr ? 0 : object->telemetryFrameCount;
    const bool hasProjection = endInfo != nullptr && endInfo->layerCount >= 1;
    const bool sampleSuccess = result == XR_SUCCESS && hasProjection &&
        compositedFrames > 0 && compositedFrames % 30 == 0;
    if (result != XR_SUCCESS || sampleSuccess) {
        recordOpenXrTrace(owner, object == nullptr ? 0 : object->telemetryId,
            hasProjection ? "COMPOSITOR" : "FRAME", "xrEndFrame", result,
            openXrTraceElapsedNs(startedAt),
            object == nullptr ? 0 : object->diagnosticFrameIndex,
            "layers=" + std::to_string(endInfo == nullptr ? 0 : endInfo->layerCount) +
                ";composited_frames=" + std::to_string(compositedFrames));
    }
    return result;
}

XrResult XRAPI_CALL endxrCreateSwapchain(
    XrSession session, const XrSwapchainCreateInfo* createInfo, XrSwapchain* swapchain
) {
    XrSession_T* object = getSession(session);
    XrInstance_T* owner = object == nullptr ? nullptr : getInstance(object->owner);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrCreateSwapchainImpl(session, createInfo, swapchain);
    const std::string details = "width=" +
        std::to_string(createInfo == nullptr ? 0 : createInfo->width) +
        ";height=" + std::to_string(createInfo == nullptr ? 0 : createInfo->height) +
        ";format=" + std::to_string(createInfo == nullptr ? 0 : createInfo->format) +
        ";usage_flags=" + std::to_string(createInfo == nullptr ? 0 : createInfo->usageFlags) +
        ";image_count=3";
    recordOpenXrTrace(owner, object == nullptr ? 0 : object->telemetryId,
        "SWAPCHAIN", "xrCreateSwapchain", result, openXrTraceElapsedNs(startedAt),
        object == nullptr ? 0 : object->diagnosticFrameIndex, details);
    return result;
}

XrResult XRAPI_CALL endxrAcquireSwapchainImage(
    XrSwapchain swapchain, const XrSwapchainImageAcquireInfo* acquireInfo,
    std::uint32_t* index
) {
    XrSwapchain_T* chain = getSwapchain(swapchain);
    XrSession_T* session = chain == nullptr ? nullptr : getSession(chain->owner);
    XrInstance_T* owner = session == nullptr ? nullptr : getInstance(session->owner);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrAcquireSwapchainImageImpl(swapchain, acquireInfo, index);
    const std::uint64_t frameIndex =
        session == nullptr ? 0 : session->diagnosticFrameIndex;
    if (result != XR_SUCCESS || (frameIndex > 0 && frameIndex % 30 == 0)) {
        recordOpenXrTrace(owner, session == nullptr ? 0 : session->telemetryId,
            "SWAPCHAIN", "xrAcquireSwapchainImage", result, openXrTraceElapsedNs(startedAt),
            frameIndex, "image_index=" +
                std::to_string(result == XR_SUCCESS && index != nullptr ? *index : 0));
    }
    return result;
}

XrResult XRAPI_CALL endxrWaitSwapchainImage(
    XrSwapchain swapchain, const XrSwapchainImageWaitInfo* waitInfo
) {
    XrSwapchain_T* chain = getSwapchain(swapchain);
    XrSession_T* session = chain == nullptr ? nullptr : getSession(chain->owner);
    XrInstance_T* owner = session == nullptr ? nullptr : getInstance(session->owner);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrWaitSwapchainImageImpl(swapchain, waitInfo);
    const std::uint64_t frameIndex =
        session == nullptr ? 0 : session->diagnosticFrameIndex;
    if (result != XR_SUCCESS || (frameIndex > 0 && frameIndex % 30 == 0)) {
        recordOpenXrTrace(owner, session == nullptr ? 0 : session->telemetryId,
            "SWAPCHAIN", "xrWaitSwapchainImage", result, openXrTraceElapsedNs(startedAt),
            frameIndex, "acquired_index=" +
                std::to_string(chain == nullptr ? 0 : chain->acquiredIndex));
    }
    return result;
}

XrResult XRAPI_CALL endxrReleaseSwapchainImage(
    XrSwapchain swapchain, const XrSwapchainImageReleaseInfo* releaseInfo
) {
    XrSwapchain_T* chain = getSwapchain(swapchain);
    XrSession_T* session = chain == nullptr ? nullptr : getSession(chain->owner);
    XrInstance_T* owner = session == nullptr ? nullptr : getInstance(session->owner);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result = endxrReleaseSwapchainImageImpl(swapchain, releaseInfo);
    const std::uint64_t frameIndex =
        session == nullptr ? 0 : session->diagnosticFrameIndex;
    if (result != XR_SUCCESS || (frameIndex > 0 && frameIndex % 30 == 0)) {
        recordOpenXrTrace(owner, session == nullptr ? 0 : session->telemetryId,
            "SWAPCHAIN", "xrReleaseSwapchainImage", result, openXrTraceElapsedNs(startedAt),
            frameIndex, "released_index=" +
                std::to_string(chain == nullptr ? 0 : chain->lastReleasedIndex));
    }
    return result;
}

XrResult XRAPI_CALL endxrLocateViews(
    XrSession session, const XrViewLocateInfo* locateInfo, XrViewState* viewState,
    std::uint32_t capacity, std::uint32_t* count, XrView* views
) {
    XrSession_T* object = getSession(session);
    XrInstance_T* owner = object == nullptr ? nullptr : getInstance(object->owner);
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result =
        endxrLocateViewsImpl(session, locateInfo, viewState, capacity, count, views);
    const std::uint64_t frameIndex =
        object == nullptr ? 0 : object->diagnosticFrameIndex;
    if (result != XR_SUCCESS || (frameIndex > 0 && frameIndex % 30 == 0)) {
        recordOpenXrTrace(owner, object == nullptr ? 0 : object->telemetryId,
            "FRAME", "xrLocateViews", result, openXrTraceElapsedNs(startedAt),
            frameIndex, "view_count=" +
                std::to_string(result == XR_SUCCESS && count != nullptr ? *count : 0));
    }
    return result;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrLocateSpaces(
    XrSession session, const XrSpacesLocateInfo* locateInfo, XrSpaceLocations* spaceLocations
) {
    if (getSession(session) == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (locateInfo == nullptr || locateInfo->type != XR_TYPE_SPACES_LOCATE_INFO ||
        spaceLocations == nullptr || spaceLocations->type != XR_TYPE_SPACE_LOCATIONS) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrSpace_T* base = getSpace(locateInfo->baseSpace);
    if (base == nullptr || base->owner != session) return XR_ERROR_HANDLE_INVALID;
    if (locateInfo->time <= 0) return XR_ERROR_TIME_INVALID;
    if (locateInfo->spaceCount > 0 &&
        (locateInfo->spaces == nullptr || spaceLocations->locations == nullptr ||
         spaceLocations->locationCount < locateInfo->spaceCount)) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    for (std::uint32_t i = 0; i < locateInfo->spaceCount; ++i) {
        XrSpaceLocation single{};
        single.type = XR_TYPE_SPACE_LOCATION;
        const XrResult result = endxrLocateSpace(
            locateInfo->spaces[i], locateInfo->baseSpace, locateInfo->time, &single);
        if (XR_FAILED(result)) return result;
        spaceLocations->locations[i].locationFlags = single.locationFlags;
        spaceLocations->locations[i].pose = single.pose;
    }
    spaceLocations->locationCount = locateInfo->spaceCount;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrSetAndroidApplicationThreadKHR(
    XrSession session, XrAndroidThreadTypeKHR threadType, std::uint32_t threadId
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrInstance_T* owner = getInstance(object->owner);
    if (owner == nullptr || !owner->androidThreadSettingsExtensionEnabled) {
        return XR_ERROR_EXTENSION_NOT_PRESENT;
    }
    switch (threadType) {
        case XR_ANDROID_THREAD_TYPE_APPLICATION_MAIN_KHR:
        case XR_ANDROID_THREAD_TYPE_APPLICATION_WORKER_KHR:
        case XR_ANDROID_THREAD_TYPE_RENDERER_MAIN_KHR:
        case XR_ANDROID_THREAD_TYPE_RENDERER_WORKER_KHR:
            break;
        default:
            return XR_ERROR_VALIDATION_FAILURE;
    }
    if (threadId == 0) return XR_ERROR_ANDROID_THREAD_SETTINGS_ID_INVALID_KHR;
#if defined(__ANDROID__)
    const int niceValue =
        (threadType == XR_ANDROID_THREAD_TYPE_RENDERER_MAIN_KHR ||
         threadType == XR_ANDROID_THREAD_TYPE_RENDERER_WORKER_KHR)
            ? -4 : 0;
    (void)setpriority(PRIO_PROCESS, static_cast<id_t>(threadId), niceValue);
#else
    (void)threadId;
#endif
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrConvertTimespecTimeToTimeKHR(
    XrInstance instance, const struct timespec* timespecTime, XrTime* time
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (!owner->convertTimespecTimeExtensionEnabled) return XR_ERROR_EXTENSION_NOT_PRESENT;
    if (timespecTime == nullptr || time == nullptr ||
        timespecTime->tv_sec < 0 || timespecTime->tv_nsec < 0 ||
        timespecTime->tv_nsec >= 1000000000L) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *time = static_cast<XrTime>(timespecTime->tv_sec) * 1000000000LL +
        static_cast<XrTime>(timespecTime->tv_nsec);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrConvertTimeToTimespecTimeKHR(
    XrInstance instance, XrTime time, struct timespec* timespecTime
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (!owner->convertTimespecTimeExtensionEnabled) return XR_ERROR_EXTENSION_NOT_PRESENT;
    if (timespecTime == nullptr || time <= 0) return XR_ERROR_VALIDATION_FAILURE;
    timespecTime->tv_sec = static_cast<decltype(timespecTime->tv_sec)>(time / 1000000000LL);
    timespecTime->tv_nsec = static_cast<decltype(timespecTime->tv_nsec)>(time % 1000000000LL);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrPerfSettingsSetPerformanceLevelEXT(
    XrSession session, XrPerfSettingsDomainEXT domain, XrPerfSettingsLevelEXT level
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrInstance_T* owner = getInstance(object->owner);
    if (owner == nullptr || !owner->performanceSettingsExtensionEnabled) {
        return XR_ERROR_EXTENSION_NOT_PRESENT;
    }
    if (domain != XR_PERF_SETTINGS_DOMAIN_CPU_EXT &&
        domain != XR_PERF_SETTINGS_DOMAIN_GPU_EXT) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    switch (level) {
        case XR_PERF_SETTINGS_LEVEL_POWER_SAVINGS_EXT:
        case XR_PERF_SETTINGS_LEVEL_SUSTAINED_LOW_EXT:
        case XR_PERF_SETTINGS_LEVEL_SUSTAINED_HIGH_EXT:
        case XR_PERF_SETTINGS_LEVEL_BOOST_EXT:
            return XR_SUCCESS;
        default:
            return XR_ERROR_VALIDATION_FAILURE;
    }
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateDisplayRefreshRatesFB(
    XrSession session, std::uint32_t capacity, std::uint32_t* count, float* rates
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrInstance_T* owner = getInstance(object->owner);
    if (owner == nullptr || !owner->displayRefreshRateExtensionEnabled) {
        return XR_ERROR_EXTENSION_NOT_PRESENT;
    }
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = static_cast<std::uint32_t>(kSupportedRefreshRatesHz.size());
    if (capacity == 0) return XR_SUCCESS;
    if (rates == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const std::uint32_t fillCount = std::min(capacity, *count);
    for (std::uint32_t i = 0; i < fillCount; ++i) {
        rates[i] = kSupportedRefreshRatesHz[i];
    }
    return capacity < *count ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetDisplayRefreshRateFB(
    XrSession session, float* displayRefreshRate
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrInstance_T* owner = getInstance(object->owner);
    if (owner == nullptr || !owner->displayRefreshRateExtensionEnabled) {
        return XR_ERROR_EXTENSION_NOT_PRESENT;
    }
    if (displayRefreshRate == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *displayRefreshRate = object->displayRefreshRateHz;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrRequestDisplayRefreshRateFB(
    XrSession session, float displayRefreshRate
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrInstance_T* owner = getInstance(object->owner);
    if (owner == nullptr || !owner->displayRefreshRateExtensionEnabled) {
        return XR_ERROR_EXTENSION_NOT_PRESENT;
    }
    float target = displayRefreshRate;
    if (target == 0.0f) {
        target = 60.0f;
    } else {
        bool supported = false;
        for (float rate : kSupportedRefreshRatesHz) {
            if (std::abs(rate - target) < 0.5f) {
                target = rate;
                supported = true;
                break;
            }
        }
        if (!supported) return XR_ERROR_DISPLAY_REFRESH_RATE_UNSUPPORTED_FB;
    }
    const float previous = object->displayRefreshRateHz;
    if (std::abs(previous - target) >= 0.5f) {
        object->displayRefreshRateHz = target;
        XrEventDataDisplayRefreshRateChangedFB changed{};
        changed.type = XR_TYPE_EVENT_DATA_DISPLAY_REFRESH_RATE_CHANGED_FB;
        changed.fromDisplayRefreshRate = previous;
        changed.toDisplayRefreshRate = target;
        queueInstanceEvent(owner, changed);
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateColorSpacesFB(
    XrSession session, std::uint32_t capacity, std::uint32_t* count, XrColorSpaceFB* colorSpaces
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrInstance_T* owner = getInstance(object->owner);
    if (owner == nullptr || !owner->colorSpaceExtensionEnabled) {
        return XR_ERROR_EXTENSION_NOT_PRESENT;
    }
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = static_cast<std::uint32_t>(kSupportedColorSpacesFB.size());
    if (capacity == 0) return XR_SUCCESS;
    if (colorSpaces == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const std::uint32_t fillCount = std::min(capacity, *count);
    for (std::uint32_t i = 0; i < fillCount; ++i) {
        colorSpaces[i] = kSupportedColorSpacesFB[i];
    }
    return capacity < *count ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrSetColorSpaceFB(
    XrSession session, const XrColorSpaceFB colorSpace
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrInstance_T* owner = getInstance(object->owner);
    if (owner == nullptr || !owner->colorSpaceExtensionEnabled) {
        return XR_ERROR_EXTENSION_NOT_PRESENT;
    }
    if (std::find(kSupportedColorSpacesFB.begin(), kSupportedColorSpacesFB.end(), colorSpace) ==
        kSupportedColorSpacesFB.end()) {
        return XR_ERROR_COLOR_SPACE_UNSUPPORTED_FB;
    }
    object->colorSpace = colorSpace;
    return XR_SUCCESS;
}

#define ENDXR_PROC(name, fn) {name, reinterpret_cast<PFN_xrVoidFunction>(fn)}
struct ProcEntry { const char* name; PFN_xrVoidFunction function; };
const std::array<ProcEntry, 69> kProcTable{{
    ENDXR_PROC("xrGetInstanceProcAddr", endxrGetInstanceProcAddr),
    ENDXR_PROC("xrEnumerateApiLayerProperties", endxrEnumerateApiLayerProperties),
    ENDXR_PROC("xrEnumerateInstanceExtensionProperties", endxrEnumerateInstanceExtensionProperties),
    ENDXR_PROC("xrCreateInstance", endxrCreateInstance),
    ENDXR_PROC("xrDestroyInstance", endxrDestroyInstance),
    ENDXR_PROC("xrGetInstanceProperties", endxrGetInstanceProperties),
    ENDXR_PROC("xrPollEvent", endxrPollEvent),
    ENDXR_PROC("xrResultToString", endxrResultToString),
    ENDXR_PROC("xrStructureTypeToString", endxrStructureTypeToString),
    ENDXR_PROC("xrGetSystem", endxrGetSystem),
    ENDXR_PROC("xrGetSystemProperties", endxrGetSystemProperties),
    ENDXR_PROC("xrEnumerateViewConfigurations", endxrEnumerateViewConfigurations),
    ENDXR_PROC("xrGetViewConfigurationProperties", endxrGetViewConfigurationProperties),
    ENDXR_PROC("xrEnumerateViewConfigurationViews", endxrEnumerateViewConfigurationViews),
    ENDXR_PROC("xrEnumerateEnvironmentBlendModes", endxrEnumerateEnvironmentBlendModes),
    ENDXR_PROC("xrGetOpenGLESGraphicsRequirementsKHR", endxrGetOpenGLESGraphicsRequirementsKHR),
    ENDXR_PROC("xrCreateSession", endxrCreateSession),
    ENDXR_PROC("xrDestroySession", endxrDestroySession),
    ENDXR_PROC("xrBeginSession", endxrBeginSession),
    ENDXR_PROC("xrRequestExitSession", endxrRequestExitSession),
    ENDXR_PROC("xrEndSession", endxrEndSession),
    ENDXR_PROC("xrWaitFrame", endxrWaitFrame),
    ENDXR_PROC("xrBeginFrame", endxrBeginFrame),
    ENDXR_PROC("xrEndFrame", endxrEndFrame),
    ENDXR_PROC("xrEnumerateReferenceSpaces", endxrEnumerateReferenceSpaces),
    ENDXR_PROC("xrGetReferenceSpaceBoundsRect", endxrGetReferenceSpaceBoundsRect),
    ENDXR_PROC("xrCreateReferenceSpace", endxrCreateReferenceSpace),
    ENDXR_PROC("xrDestroySpace", endxrDestroySpace),
    ENDXR_PROC("xrLocateSpace", endxrLocateSpace),
    ENDXR_PROC("xrLocateViews", endxrLocateViews),
    ENDXR_PROC("xrEnumerateSwapchainFormats", endxrEnumerateSwapchainFormats),
    ENDXR_PROC("xrCreateSwapchain", endxrCreateSwapchain),
    ENDXR_PROC("xrDestroySwapchain", endxrDestroySwapchain),
    ENDXR_PROC("xrEnumerateSwapchainImages", endxrEnumerateSwapchainImages),
    ENDXR_PROC("xrAcquireSwapchainImage", endxrAcquireSwapchainImage),
    ENDXR_PROC("xrWaitSwapchainImage", endxrWaitSwapchainImage),
    ENDXR_PROC("xrStringToPath", endxrStringToPath),
    ENDXR_PROC("xrPathToString", endxrPathToString),
    ENDXR_PROC("xrCreateActionSet", endxrCreateActionSet),
    ENDXR_PROC("xrDestroyActionSet", endxrDestroyActionSet),
    ENDXR_PROC("xrCreateAction", endxrCreateAction),
    ENDXR_PROC("xrDestroyAction", endxrDestroyAction),
    ENDXR_PROC("xrSuggestInteractionProfileBindings", endxrSuggestInteractionProfileBindings),
    ENDXR_PROC("xrAttachSessionActionSets", endxrAttachSessionActionSets),
    ENDXR_PROC("xrGetCurrentInteractionProfile", endxrGetCurrentInteractionProfile),
    ENDXR_PROC("xrGetActionStateBoolean", endxrGetActionStateBoolean),
    ENDXR_PROC("xrGetActionStateFloat", endxrGetActionStateFloat),
    ENDXR_PROC("xrGetActionStateVector2f", endxrGetActionStateVector2f),
    ENDXR_PROC("xrGetActionStatePose", endxrGetActionStatePose),
    ENDXR_PROC("xrSyncActions", endxrSyncActions),
    ENDXR_PROC("xrEnumerateBoundSourcesForAction", endxrEnumerateBoundSourcesForAction),
    ENDXR_PROC("xrGetInputSourceLocalizedName", endxrGetInputSourceLocalizedName),
    ENDXR_PROC("xrCreateActionSpace", endxrCreateActionSpace),
    ENDXR_PROC("xrApplyHapticFeedback", endxrApplyHapticFeedback),



    ENDXR_PROC("xrStopHapticFeedback", endxrStopHapticFeedback),
    ENDXR_PROC("xrCreateHandTrackerEXT", endxrCreateHandTrackerEXT),
    ENDXR_PROC("xrDestroyHandTrackerEXT", endxrDestroyHandTrackerEXT),
    ENDXR_PROC("xrLocateHandJointsEXT", endxrLocateHandJointsEXT),
    ENDXR_PROC("xrLocateSpaces", endxrLocateSpaces),
    ENDXR_PROC("xrLocateSpacesKHR", endxrLocateSpaces),
    ENDXR_PROC("xrSetAndroidApplicationThreadKHR", endxrSetAndroidApplicationThreadKHR),
    ENDXR_PROC("xrConvertTimespecTimeToTimeKHR", endxrConvertTimespecTimeToTimeKHR),
    ENDXR_PROC("xrConvertTimeToTimespecTimeKHR", endxrConvertTimeToTimespecTimeKHR),
    ENDXR_PROC("xrPerfSettingsSetPerformanceLevelEXT", endxrPerfSettingsSetPerformanceLevelEXT),
    ENDXR_PROC("xrEnumerateDisplayRefreshRatesFB", endxrEnumerateDisplayRefreshRatesFB),
    ENDXR_PROC("xrGetDisplayRefreshRateFB", endxrGetDisplayRefreshRateFB),
    ENDXR_PROC("xrRequestDisplayRefreshRateFB", endxrRequestDisplayRefreshRateFB),
    ENDXR_PROC("xrEnumerateColorSpacesFB", endxrEnumerateColorSpacesFB),
    ENDXR_PROC("xrSetColorSpaceFB", endxrSetColorSpaceFB)
}};
#undef ENDXR_PROC

XRAPI_ATTR XrResult XRAPI_CALL endxrGetInstanceProcAddr(
    XrInstance instance, const char* name, PFN_xrVoidFunction* function
) {
    if (function == nullptr || name == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *function = nullptr;
    // OpenXR permits XR_NULL_HANDLE only for the three global entry points.
    // Returning core/extension commands here makes clients believe those
    // commands can be called before an instance exists and violates the
    // loader-facing dispatch contract.
    const bool isGlobalCommand =
        std::strcmp(name, "xrEnumerateInstanceExtensionProperties") == 0 ||
        std::strcmp(name, "xrEnumerateApiLayerProperties") == 0 ||
        std::strcmp(name, "xrCreateInstance") == 0;
    XrInstance_T* owner = nullptr;
    if (instance == XR_NULL_HANDLE) {
        if (!isGlobalCommand) return XR_ERROR_HANDLE_INVALID;
    } else {
        owner = getInstance(instance);
        if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    }

    for (const auto& entry : kProcTable) {
        if (std::strcmp(name, entry.name) == 0) {
            if (std::strcmp(name, "xrGetOpenGLESGraphicsRequirementsKHR") == 0 &&
                (owner == nullptr || !owner->glesExtensionEnabled)) {
                return owner == nullptr
                    ? XR_ERROR_HANDLE_INVALID
                    : XR_ERROR_FUNCTION_UNSUPPORTED;
            }
            const bool isHandTrackingCommand =
                std::strcmp(name, "xrCreateHandTrackerEXT") == 0 ||
                std::strcmp(name, "xrDestroyHandTrackerEXT") == 0 ||
                std::strcmp(name, "xrLocateHandJointsEXT") == 0;
            if (isHandTrackingCommand &&
                (owner == nullptr || !owner->handTrackingExtensionEnabled)) {
                return owner == nullptr
                    ? XR_ERROR_HANDLE_INVALID
                    : XR_ERROR_FUNCTION_UNSUPPORTED;
            }
            if (std::strcmp(name, "xrLocateSpaces") == 0 &&
                (owner == nullptr || owner->requestedApiVersion < XR_MAKE_VERSION(1, 1, 0))) {
                return owner == nullptr
                    ? XR_ERROR_HANDLE_INVALID
                    : XR_ERROR_FUNCTION_UNSUPPORTED;
            }
            if (std::strcmp(name, "xrLocateSpacesKHR") == 0 &&
                (owner == nullptr || !owner->locateSpacesExtensionEnabled)) {
                return owner == nullptr
                    ? XR_ERROR_HANDLE_INVALID
                    : XR_ERROR_FUNCTION_UNSUPPORTED;
            }
            if (std::strcmp(name, "xrSetAndroidApplicationThreadKHR") == 0 &&
                (owner == nullptr || !owner->androidThreadSettingsExtensionEnabled)) {
                return owner == nullptr
                    ? XR_ERROR_HANDLE_INVALID
                    : XR_ERROR_FUNCTION_UNSUPPORTED;
            }
            if ((std::strcmp(name, "xrConvertTimespecTimeToTimeKHR") == 0 ||
                 std::strcmp(name, "xrConvertTimeToTimespecTimeKHR") == 0) &&
                (owner == nullptr || !owner->convertTimespecTimeExtensionEnabled)) {
                return owner == nullptr
                    ? XR_ERROR_HANDLE_INVALID
                    : XR_ERROR_FUNCTION_UNSUPPORTED;
            }
            if (std::strcmp(name, "xrPerfSettingsSetPerformanceLevelEXT") == 0 &&
                (owner == nullptr || !owner->performanceSettingsExtensionEnabled)) {
                return owner == nullptr
                    ? XR_ERROR_HANDLE_INVALID
                    : XR_ERROR_FUNCTION_UNSUPPORTED;
            }
            if ((std::strcmp(name, "xrEnumerateDisplayRefreshRatesFB") == 0 ||
                 std::strcmp(name, "xrGetDisplayRefreshRateFB") == 0 ||
                 std::strcmp(name, "xrRequestDisplayRefreshRateFB") == 0) &&
                (owner == nullptr || !owner->displayRefreshRateExtensionEnabled)) {
                return owner == nullptr
                    ? XR_ERROR_HANDLE_INVALID
                    : XR_ERROR_FUNCTION_UNSUPPORTED;
            }
            if ((std::strcmp(name, "xrEnumerateColorSpacesFB") == 0 ||
                 std::strcmp(name, "xrSetColorSpaceFB") == 0) &&
                (owner == nullptr || !owner->colorSpaceExtensionEnabled)) {
                return owner == nullptr
                    ? XR_ERROR_HANDLE_INVALID
                    : XR_ERROR_FUNCTION_UNSUPPORTED;
            }
            *function = entry.function;
            return XR_SUCCESS;
        }
    }
    if (std::strcmp(name, "xrReleaseSwapchainImage") == 0) {
        *function = reinterpret_cast<PFN_xrVoidFunction>(endxrReleaseSwapchainImage);
        return XR_SUCCESS;
    }
    return XR_ERROR_FUNCTION_UNSUPPORTED;
}
} // namespace

extern "C" __attribute__((visibility("default"))) XRAPI_ATTR XrResult XRAPI_CALL
xrNegotiateLoaderRuntimeInterfaceImpl(
    const XrNegotiateLoaderInfo* loaderInfo,
    XrNegotiateRuntimeRequest* runtimeRequest
) {
    if (loaderInfo == nullptr || runtimeRequest == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (loaderInfo->structType != XR_LOADER_INTERFACE_STRUCT_LOADER_INFO ||
        loaderInfo->structVersion != XR_LOADER_INFO_STRUCT_VERSION ||
        loaderInfo->structSize < sizeof(XrNegotiateLoaderInfo) ||
        runtimeRequest->structType != XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST ||
        runtimeRequest->structVersion != XR_RUNTIME_INFO_STRUCT_VERSION ||
        runtimeRequest->structSize < sizeof(XrNegotiateRuntimeRequest)) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    if (loaderInfo->minInterfaceVersion > XR_CURRENT_LOADER_RUNTIME_VERSION ||
        loaderInfo->maxInterfaceVersion < XR_CURRENT_LOADER_RUNTIME_VERSION) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    if (loaderInfo->minApiVersion > kEndXrApiVersion ||
        loaderInfo->maxApiVersion < kMinEndXrApiVersion) {
        return XR_ERROR_API_VERSION_UNSUPPORTED;
    }
    const XrVersion negotiatedApiVersion = std::max(
        kMinEndXrApiVersion,
        std::min(loaderInfo->maxApiVersion, kEndXrApiVersion)
    );
    runtimeRequest->runtimeInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    runtimeRequest->runtimeApiVersion = negotiatedApiVersion;
    runtimeRequest->getInstanceProcAddr = endxrGetInstanceProcAddr;
    return XR_SUCCESS;
}
 
extern "C" __attribute__((visibility("default"))) XRAPI_ATTR XrResult XRAPI_CALL
xrNegotiateLoaderRuntimeInterface(
    const XrNegotiateLoaderInfo* loaderInfo,
    XrNegotiateRuntimeRequest* runtimeRequest
) {
    const auto startedAt = std::chrono::steady_clock::now();
    const XrResult result =
        xrNegotiateLoaderRuntimeInterfaceImpl(loaderInfo, runtimeRequest);
    const std::uint64_t durationNs = openXrTraceElapsedNs(startedAt);
    std::string details = "loader_info_valid=" +
        std::string(loaderInfo == nullptr ? "false" : "true");
    if (loaderInfo != nullptr) {
        details += ";interface_min=" + std::to_string(loaderInfo->minInterfaceVersion) +
            ";interface_max=" + std::to_string(loaderInfo->maxInterfaceVersion) +
            ";api_min=" + std::to_string(XR_VERSION_MAJOR(loaderInfo->minApiVersion)) + "." +
            std::to_string(XR_VERSION_MINOR(loaderInfo->minApiVersion)) + "." +
            std::to_string(XR_VERSION_PATCH(loaderInfo->minApiVersion)) +
            ";api_max=" + std::to_string(XR_VERSION_MAJOR(loaderInfo->maxApiVersion)) + "." +
            std::to_string(XR_VERSION_MINOR(loaderInfo->maxApiVersion)) + "." +
            std::to_string(XR_VERSION_PATCH(loaderInfo->maxApiVersion));
    }
    if (result == XR_SUCCESS && runtimeRequest != nullptr) {
        details += ";runtime_interface=" +
            std::to_string(runtimeRequest->runtimeInterfaceVersion) +
            ";runtime_api=" + std::to_string(XR_VERSION_MAJOR(runtimeRequest->runtimeApiVersion)) +
            "." + std::to_string(XR_VERSION_MINOR(runtimeRequest->runtimeApiVersion)) +
            "." + std::to_string(XR_VERSION_PATCH(runtimeRequest->runtimeApiVersion));
    }
#if defined(__ANDROID__)
    __android_log_print(
        result < 0 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
        "EndXRTrace",
        "stage=LOADER operation=xrNegotiateLoaderRuntimeInterface result=%s code=%d duration_ns=%llu %s",
        openXrTraceResultName(result), static_cast<int>(result),
        static_cast<unsigned long long>(durationNs), details.c_str()
    );
#endif
    {
        std::lock_guard<std::mutex> lock(g_loaderTraceMutex);
        g_loaderTracePending = result == XR_SUCCESS;
        g_loaderTraceResult = result;
        g_loaderTraceDurationNs = durationNs;
        g_loaderTraceDetails = details;
    }
    return result;
}


extern "C" __attribute__((visibility("default"))) void endxrSetControllerInput(
    std::uint32_t hand, std::uint32_t active, float trigger, float grip,
    float stickX, float stickY, std::uint32_t buttons
) {
    if (hand > 1) return;
    auto clean = [](float value) { return std::isfinite(value) ? std::clamp(value, -1.0f, 1.0f) : 0.0f; };
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    auto& input = g_controllerInputs[hand];
    input.active = active != 0;
    input.trigger = std::clamp(clean(trigger), 0.0f, 1.0f);
    input.grip = std::clamp(clean(grip), 0.0f, 1.0f);
    input.stickX = clean(stickX);
    input.stickY = clean(stickY);
    input.buttons = buttons;
    input.updatedAt = std::chrono::steady_clock::now();
}
extern "C" __attribute__((visibility("default"))) void endxrClearControllerInput() {
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    g_controllerInputs = {};
}
extern "C" __attribute__((visibility("default"))) void endxrSetHandPose(
    std::uint32_t hand, std::uint32_t active,
    float x, float y, float z, float qx, float qy, float qz, float qw
) {
    if (hand > 1) return;
    const float values[] = {x, y, z, qx, qy, qz, qw};
    for (float value : values) if (!std::isfinite(value)) active = 0;
    const double magnitude = std::sqrt(
        static_cast<double>(qx) * qx + static_cast<double>(qy) * qy +
        static_cast<double>(qz) * qz + static_cast<double>(qw) * qw
    );
    if (!std::isfinite(magnitude) || magnitude < 0.5 || magnitude > 1.5 ||
        std::abs(x) > 8.0f || std::abs(y) > 6.0f || z > -0.25f || z < -6.0f) {
        active = 0;
    }
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    auto& input = g_controllerInputs[hand];
    input.poseActive = active != 0;
    input.updatedAt = std::chrono::steady_clock::now();
    if (input.poseActive) {
        input.gripPose.orientation = {
            static_cast<float>(qx / magnitude),
            static_cast<float>(qy / magnitude),
            static_cast<float>(qz / magnitude),
            static_cast<float>(qw / magnitude)
        };
        input.gripPose.position = {x, y, z};
    } else {
        input.gripPose = {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
        input.handJointsActive = false;
        input.handJoints = {};
    }
}
extern "C" __attribute__((visibility("default"))) void endxrSetHandJointData(
    std::uint32_t hand, std::uint32_t active,
    const float* values, std::uint32_t floatCount
) {
    if (hand > 1) return;
    constexpr std::uint32_t expectedCount = XR_HAND_JOINT_COUNT_EXT * 8u;
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    auto& input = g_controllerInputs[hand];
    input.updatedAt = std::chrono::steady_clock::now();
    input.handJointsActive = false;
    input.handJoints = {};
    if (active == 0 || !input.poseActive || values == nullptr ||
        floatCount != expectedCount) return;

    std::array<float, XR_HAND_JOINT_COUNT_EXT * 8u> sanitized{};
    for (std::uint32_t joint = 0; joint < XR_HAND_JOINT_COUNT_EXT; ++joint) {
        const std::uint32_t offset = joint * 8u;
        for (std::uint32_t component = 0; component < 8u; ++component) {
            if (!std::isfinite(values[offset + component])) return;
        }
        if (std::abs(values[offset]) > 0.24f ||
            std::abs(values[offset + 1]) > 0.24f ||
            std::abs(values[offset + 2]) > 0.24f ||
            values[offset + 3] < 0.001f || values[offset + 3] > 0.04f) return;
        const double magnitude = std::sqrt(
            static_cast<double>(values[offset + 4]) * values[offset + 4] +
            static_cast<double>(values[offset + 5]) * values[offset + 5] +
            static_cast<double>(values[offset + 6]) * values[offset + 6] +
            static_cast<double>(values[offset + 7]) * values[offset + 7]
        );
        if (!std::isfinite(magnitude) || magnitude < 0.5 || magnitude > 1.5) return;
        for (std::uint32_t component = 0; component < 4u; ++component) {
            sanitized[offset + component] = values[offset + component];
        }
        for (std::uint32_t component = 0; component < 4u; ++component) {
            sanitized[offset + 4u + component] = static_cast<float>(
                values[offset + 4u + component] / magnitude
            );
        }
    }
    input.handJoints = sanitized;
    input.handJointsActive = true;
}
extern "C" __attribute__((visibility("default"))) void endxrClearHandPoses() {
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    for (auto& input : g_controllerInputs) {
        input.poseActive = false;
        input.handJointsActive = false;
        input.handJoints = {};
        input.gripPose = {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
    }
}
extern "C" __attribute__((visibility("default"))) void endxrSetHeadOrientation(
    float x, float y, float z, float w
) {
    const double magnitude = std::sqrt(
        static_cast<double>(x) * x + static_cast<double>(y) * y +
        static_cast<double>(z) * z + static_cast<double>(w) * w
    );
    if (!std::isfinite(magnitude) || magnitude < 0.5 || magnitude > 1.5) return;
    std::lock_guard<std::mutex> lock(g_headPoseMutex);
    g_headOrientation = {
        static_cast<float>(x / magnitude),
        static_cast<float>(y / magnitude),
        static_cast<float>(z / magnitude),
        static_cast<float>(w / magnitude)
    };
    g_headOrientationUpdatedAt = std::chrono::steady_clock::now();
    g_hasHeadOrientation = true;
}

extern "C" __attribute__((visibility("default"))) void endxrClearHeadOrientation() {
    std::lock_guard<std::mutex> lock(g_headPoseMutex);
    g_headOrientation = {0.0f, 0.0f, 0.0f, 1.0f};
    g_headOrientationUpdatedAt = {};
    g_hasHeadOrientation = false;
}

extern "C" __attribute__((visibility("default"))) std::uint32_t
endxrGetLastGlesError() { return g_lastGlesError; }
