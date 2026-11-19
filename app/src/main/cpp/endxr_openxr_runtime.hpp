#pragma once

#include "third_party/openxr/include/openxr/openxr_loader_negotiation.h"

#include <cstdint>
#include <ctime>

#define ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME "XR_KHR_android_create_instance"
#define ENDXR_KHR_ANDROID_CREATE_INSTANCE_SPEC_VERSION 3
#define ENDXR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME "XR_KHR_opengl_es_enable"
#define ENDXR_KHR_OPENGL_ES_ENABLE_SPEC_VERSION 10
#define ENDXR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME "XR_KHR_android_thread_settings"
#define ENDXR_KHR_ANDROID_THREAD_SETTINGS_SPEC_VERSION 6
#define ENDXR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME "XR_KHR_convert_timespec_time"
#define ENDXR_KHR_CONVERT_TIMESPEC_TIME_SPEC_VERSION 1

typedef enum XrAndroidThreadTypeKHR {
    XR_ANDROID_THREAD_TYPE_APPLICATION_MAIN_KHR = 1,
    XR_ANDROID_THREAD_TYPE_APPLICATION_WORKER_KHR = 2,
    XR_ANDROID_THREAD_TYPE_RENDERER_MAIN_KHR = 3,
    XR_ANDROID_THREAD_TYPE_RENDERER_WORKER_KHR = 4,
    XR_ANDROID_THREAD_TYPE_MAX_ENUM_KHR = 0x7FFFFFFF
} XrAndroidThreadTypeKHR;

typedef XrResult (XRAPI_PTR *PFN_xrSetAndroidApplicationThreadKHR)(
    XrSession session,
    XrAndroidThreadTypeKHR threadType,
    uint32_t threadId);

typedef XrResult (XRAPI_PTR *PFN_xrConvertTimespecTimeToTimeKHR)(
    XrInstance instance,
    const struct timespec* timespecTime,
    XrTime* time);

typedef XrResult (XRAPI_PTR *PFN_xrConvertTimeToTimespecTimeKHR)(
    XrInstance instance,
    XrTime time,
    struct timespec* timespecTime);

// Khronos XR_KHR_android_create_instance ABI structure.
typedef struct XrInstanceCreateInfoAndroidKHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    void* XR_MAY_ALIAS applicationVM;
    void* XR_MAY_ALIAS applicationActivity;
} XrInstanceCreateInfoAndroidKHR;

// Khronos XR_KHR_opengl_es_enable Android binding and image ABI structures.
// EGL handles are opaque pointer-sized values in the Android ABI.
typedef struct XrGraphicsBindingOpenGLESAndroidKHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    void* display;
    void* config;
    void* context;
} XrGraphicsBindingOpenGLESAndroidKHR;

typedef struct XrSwapchainImageOpenGLESKHR {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    uint32_t image;
} XrSwapchainImageOpenGLESKHR;

typedef struct XrGraphicsRequirementsOpenGLESKHR {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    XrVersion minApiVersionSupported;
    XrVersion maxApiVersionSupported;
} XrGraphicsRequirementsOpenGLESKHR;

typedef XrResult (XRAPI_PTR *PFN_xrGetOpenGLESGraphicsRequirementsKHR)(
    XrInstance instance,
    XrSystemId systemId,
    XrGraphicsRequirementsOpenGLESKHR* graphicsRequirements
);

#if defined(__cplusplus)
extern "C" {
#endif

#if defined(__GNUC__)
__attribute__((visibility("default")))
#endif
XRAPI_ATTR XrResult XRAPI_CALL xrNegotiateLoaderRuntimeInterface(
    const XrNegotiateLoaderInfo* loaderInfo,
    XrNegotiateRuntimeRequest* runtimeRequest
);

#if defined(__GNUC__)
__attribute__((visibility("default")))
#endif
void endxrSetHeadOrientation(float x, float y, float z, float w);
void endxrClearHeadOrientation();
void endxrSetControllerInput(std::uint32_t hand, std::uint32_t active,
    float trigger, float grip, float stickX, float stickY, std::uint32_t buttons);
void endxrClearControllerInput();
void endxrSetHandPose(std::uint32_t hand, std::uint32_t active,
    float x, float y, float z, float qx, float qy, float qz, float qw);
void endxrClearHandPoses();
void endxrSetHandJointData(std::uint32_t hand, std::uint32_t active,
    const float* values, std::uint32_t floatCount);
std::uint32_t endxrGetLastGlesError();

#if defined(__cplusplus)
}
#endif
