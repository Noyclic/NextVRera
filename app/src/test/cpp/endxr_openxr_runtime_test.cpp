#include "endxr_openxr_runtime.hpp"
#include <cstdint>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <chrono>
#include <thread>

namespace {
void expect(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << '\n';
        std::exit(1);
    }
}
void setup(XrNegotiateLoaderInfo& loader, XrNegotiateRuntimeRequest& runtime) {
    loader = {};
    loader.structType = XR_LOADER_INTERFACE_STRUCT_LOADER_INFO;
    loader.structVersion = XR_LOADER_INFO_STRUCT_VERSION;
    loader.structSize = sizeof(loader);
    loader.minInterfaceVersion = 1;
    loader.maxInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    loader.minApiVersion = XR_MAKE_VERSION(1, 0, 0);
    loader.maxApiVersion = XR_MAKE_VERSION(1, 1, 99);
    runtime = {};
    runtime.structType = XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST;
    runtime.structVersion = XR_RUNTIME_INFO_STRUCT_VERSION;
    runtime.structSize = sizeof(runtime);
}
XrInstance createInstance(PFN_xrGetInstanceProcAddr gipa, bool enableAndroid, bool enableGles,
                          bool enableHandTracking = false,
                          bool enableHandInteraction = false,
                          XrVersion apiVersion = XR_MAKE_VERSION(1, 0, 0),
                          const char* const* extraExtensions = nullptr,
                          std::uint32_t extraExtensionCount = 0) {
    XrInstanceCreateInfoAndroidKHR androidInfo{};
    androidInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidInfo.applicationVM = reinterpret_cast<void*>(0x1000);
    androidInfo.applicationActivity = reinterpret_cast<void*>(0x2000);
    XrInstanceCreateInfo info{};
    info.type = XR_TYPE_INSTANCE_CREATE_INFO;
    info.next = enableAndroid ? &androidInfo : nullptr;
    info.applicationInfo.apiVersion = apiVersion;
    const char* enabled[16]{};
    std::uint32_t count = 0;
    if (enableAndroid) enabled[count++] = ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME;
    if (enableGles) enabled[count++] = ENDXR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME;
    if (enableHandTracking) enabled[count++] = XR_EXT_HAND_TRACKING_EXTENSION_NAME;
    if (enableHandInteraction) enabled[count++] = XR_EXT_HAND_INTERACTION_EXTENSION_NAME;
    for (std::uint32_t i = 0; i < extraExtensionCount && count < 16; ++i) {
        enabled[count++] = extraExtensions[i];
    }
    info.enabledExtensionCount = count;
    info.enabledExtensionNames = enabled;
    PFN_xrVoidFunction raw = nullptr;
    expect(gipa(XR_NULL_HANDLE, "xrCreateInstance", &raw) == XR_SUCCESS && raw != nullptr,
           "resolve xrCreateInstance");
    XrInstance instance = XR_NULL_HANDLE;
    auto create = reinterpret_cast<PFN_xrCreateInstance>(raw);
    expect(create(&info, &instance) == XR_SUCCESS && instance != XR_NULL_HANDLE,
           "create OpenXR instance");
    return instance;
}
PFN_xrVoidFunction resolve(PFN_xrGetInstanceProcAddr gipa, XrInstance instance,
                            const char* name) {
    PFN_xrVoidFunction raw = nullptr;
    expect(gipa(instance, name, &raw) == XR_SUCCESS && raw != nullptr, name);
    return raw;
}
void testNegotiation() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate OpenXR loader interface");
    expect(request.runtimeInterfaceVersion == 1, "publish loader interface version");
    expect(request.runtimeApiVersion == XR_MAKE_VERSION(1, 1, 65),
           "negotiate OpenXR 1.1.65 when loader allows 1.1.99");
    expect(request.getInstanceProcAddr != nullptr, "publish GIPA");
    setup(loader, request);
    loader.maxApiVersion = XR_MAKE_VERSION(1, 0, 99);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS &&
               request.runtimeApiVersion == XR_MAKE_VERSION(1, 0, 99),
           "negotiate OpenXR 1.0.x cleanly for 1.0-only loaders");
    loader.maxInterfaceVersion = 0;
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) ==
               XR_ERROR_INITIALIZATION_FAILED, "reject incompatible loader interface");
    setup(loader, request);
    loader.minApiVersion = XR_MAKE_VERSION(2, 0, 0);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) ==
               XR_ERROR_API_VERSION_UNSUPPORTED, "reject unsupported major API version");
    setup(loader, request);
    loader.minApiVersion = XR_MAKE_VERSION(1, 2, 0);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) ==
               XR_ERROR_API_VERSION_UNSUPPORTED, "reject unsupported OpenXR 1.2 API version");
}
void testInstanceApiVersionCompatibility() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before API version compatibility checks");

    PFN_xrVoidFunction raw = resolve(request.getInstanceProcAddr, XR_NULL_HANDLE,
                                     "xrCreateInstance");
    auto create = reinterpret_cast<PFN_xrCreateInstance>(raw);
    XrInstanceCreateInfo info{};
    info.type = XR_TYPE_INSTANCE_CREATE_INFO;
    info.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 65);
    XrInstance instance = XR_NULL_HANDLE;
    expect(create(&info, &instance) == XR_SUCCESS && instance != XR_NULL_HANDLE,
           "accept a patch-level revision within the supported OpenXR 1.0 API line");
    raw = resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance");
    auto destroy = reinterpret_cast<PFN_xrDestroyInstance>(raw);
    expect(destroy(instance) == XR_SUCCESS,
           "destroy instance created with a compatible OpenXR 1.0 patch version");

    info.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 1, 65);
    instance = XR_NULL_HANDLE;
    expect(create(&info, &instance) == XR_SUCCESS && instance != XR_NULL_HANDLE,
           "accept OpenXR 1.1 core API applications");
    expect(destroy(instance) == XR_SUCCESS,
           "destroy OpenXR 1.1 test instance");

    info.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 2, 0);
    instance = reinterpret_cast<XrInstance>(static_cast<std::uintptr_t>(1));
    expect(create(&info, &instance) == XR_ERROR_API_VERSION_UNSUPPORTED,
           "reject unsupported OpenXR 1.2 minor API version");
    expect(instance == XR_NULL_HANDLE,
           "clear the output handle when an unsupported API version is rejected");

    info.applicationInfo.apiVersion = XR_MAKE_VERSION(2, 0, 0);
    instance = reinterpret_cast<XrInstance>(static_cast<std::uintptr_t>(1));
    expect(create(&info, &instance) == XR_ERROR_API_VERSION_UNSUPPORTED,
           "reject a different OpenXR major API version");
    expect(instance == XR_NULL_HANDLE,
           "leave no instance handle after major-version rejection");

    instance = reinterpret_cast<XrInstance>(static_cast<std::uintptr_t>(1));
    expect(create(nullptr, &instance) == XR_ERROR_VALIDATION_FAILURE,
           "reject a null instance create-info structure");
    expect(instance == XR_NULL_HANDLE,
           "clear the output handle when create-info validation fails");
}

void testExtensionEnumerationAndInstance() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before extension enumeration");
    PFN_xrVoidFunction raw = resolve(request.getInstanceProcAddr, XR_NULL_HANDLE,
                                     "xrEnumerateInstanceExtensionProperties");
    auto enumerate = reinterpret_cast<PFN_xrEnumerateInstanceExtensionProperties>(raw);
    std::uint32_t count = 0;
    expect(enumerate(nullptr, 0, &count, nullptr) == XR_SUCCESS && count == 8,
           "report all 8 supported OpenXR extensions");
    XrExtensionProperties extensions[8]{};
    for (auto& ext : extensions) ext.type = XR_TYPE_EXTENSION_PROPERTIES;
    expect(enumerate(nullptr, 8, &count, extensions) == XR_SUCCESS,
           "fill all 8 extension properties");
    expect(std::strcmp(extensions[0].extensionName,
                       ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) == 0,
           "publish android-create extension");
    expect(std::strcmp(extensions[1].extensionName,
                       ENDXR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME) == 0 &&
               extensions[1].extensionVersion == ENDXR_KHR_OPENGL_ES_ENABLE_SPEC_VERSION,
           "publish GLES enable extension version 10");
    expect(std::strcmp(extensions[2].extensionName,
                       XR_EXT_HAND_TRACKING_EXTENSION_NAME) == 0 &&
               extensions[2].extensionVersion == XR_EXT_hand_tracking_SPEC_VERSION,
           "publish XR_EXT_hand_tracking extension version 4");
    expect(std::strcmp(extensions[3].extensionName,
                       XR_EXT_HAND_INTERACTION_EXTENSION_NAME) == 0 &&
               extensions[3].extensionVersion == XR_EXT_hand_interaction_SPEC_VERSION,
           "publish XR_EXT_hand_interaction extension version 2");
    expect(std::strcmp(extensions[4].extensionName,
                       ENDXR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME) == 0 &&
               extensions[4].extensionVersion == ENDXR_KHR_ANDROID_THREAD_SETTINGS_SPEC_VERSION,
           "publish best-effort XR_KHR_android_thread_settings extension");
    expect(std::strcmp(extensions[5].extensionName,
                       ENDXR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME) == 0 &&
               extensions[5].extensionVersion == ENDXR_KHR_CONVERT_TIMESPEC_TIME_SPEC_VERSION,
           "publish XR_KHR_convert_timespec_time extension");
    expect(std::strcmp(extensions[6].extensionName,
                       XR_KHR_LOCATE_SPACES_EXTENSION_NAME) == 0 &&
               extensions[6].extensionVersion == XR_KHR_locate_spaces_SPEC_VERSION,
           "publish XR_KHR_locate_spaces extension");
    expect(std::strcmp(extensions[7].extensionName,
                       XR_EXT_LOCAL_FLOOR_EXTENSION_NAME) == 0 &&
               extensions[7].extensionVersion == XR_EXT_local_floor_SPEC_VERSION,
           "publish estimated XR_EXT_local_floor extension");
    expect(enumerate(nullptr, 2, &count, extensions) == XR_ERROR_SIZE_INSUFFICIENT,
           "report insufficient extension enumeration capacity");

    XrInstance instance = createInstance(request.getInstanceProcAddr, true, true, true);
    raw = resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance");
    auto destroy = reinterpret_cast<PFN_xrDestroyInstance>(raw);
    expect(destroy(instance) == XR_SUCCESS, "destroy OpenXR instance");

    XrInstanceCreateInfoAndroidKHR androidInfo{};
    androidInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidInfo.applicationVM = reinterpret_cast<void*>(0x1000);
    androidInfo.applicationActivity = nullptr;
    XrInstanceCreateInfo bad{};
    bad.type = XR_TYPE_INSTANCE_CREATE_INFO;
    bad.next = &androidInfo;
    bad.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    const char* enabled[] = {ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
    bad.enabledExtensionCount = 1;
    bad.enabledExtensionNames = enabled;
    raw = resolve(request.getInstanceProcAddr, XR_NULL_HANDLE, "xrCreateInstance");
    auto create = reinterpret_cast<PFN_xrCreateInstance>(raw);
    expect(create(&bad, &instance) == XR_ERROR_VALIDATION_FAILURE,
           "reject Android instance without Activity");
}
void testSystemDiscoveryAndGraphicsRequirements() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before system queries");
    XrInstance instance = createInstance(request.getInstanceProcAddr, false, true, true);
    auto getSystem = reinterpret_cast<PFN_xrGetSystem>(
        resolve(request.getInstanceProcAddr, instance, "xrGetSystem"));
    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    expect(getSystem(instance, &systemInfo, &systemId) == XR_SUCCESS,
           "select phone HMD system");
    auto getProperties = reinterpret_cast<PFN_xrGetSystemProperties>(
        resolve(request.getInstanceProcAddr, instance, "xrGetSystemProperties"));
    XrSystemColorSpacePropertiesFB colorSpaceProps{};
    colorSpaceProps.type = XR_TYPE_SYSTEM_COLOR_SPACE_PROPERTIES_FB;
    XrSystemHandTrackingPropertiesEXT handProperties{};
    handProperties.type = XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT;
    handProperties.next = &colorSpaceProps;
    XrSystemProperties properties{};
    properties.type = XR_TYPE_SYSTEM_PROPERTIES;
    properties.next = &handProperties;
    expect(getProperties(instance, systemId, &properties) == XR_SUCCESS,
           "query phone system capabilities");
    expect(handProperties.supportsHandTracking == XR_TRUE,
           "advertise XR_EXT_hand_tracking support when enabled");
    expect(colorSpaceProps.colorSpace == XR_COLOR_SPACE_REC709_FB,
           "populate native REC709 color space property on xrGetSystemProperties");
    expect(properties.graphicsProperties.maxLayerCount == 1,
           "advertise only the single stereo projection layer actually composited");
    expect(properties.trackingProperties.orientationTracking == XR_TRUE &&
               properties.trackingProperties.positionTracking == XR_FALSE,
           "advertise orientation-only tracking");
    auto getRequirements = reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(
        resolve(request.getInstanceProcAddr, instance,
                "xrGetOpenGLESGraphicsRequirementsKHR"));
    XrGraphicsRequirementsOpenGLESKHR requirements{};
    requirements.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
    expect(getRequirements(instance, systemId, &requirements) == XR_SUCCESS,
           "query GLES requirements");
    expect(requirements.minApiVersionSupported == XR_MAKE_VERSION(3, 0, 0) &&
               requirements.maxApiVersionSupported == XR_MAKE_VERSION(3, 2, 0),
           "publish GLES 3.0 through 3.2 implementation level");
    auto enumerateViews = reinterpret_cast<PFN_xrEnumerateViewConfigurationViews>(
        resolve(request.getInstanceProcAddr, instance,
                "xrEnumerateViewConfigurationViews"));
    std::uint32_t count = 0;
    expect(enumerateViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                          0, &count, nullptr) == XR_SUCCESS && count == 2,
           "report two stereo views");
    XrViewConfigurationView views[2]{};
    views[0].type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
    views[1].type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
    expect(enumerateViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                          2, &count, views) == XR_SUCCESS &&
               views[0].recommendedImageRectWidth == 960 &&
               views[1].recommendedImageRectHeight == 1080,
           "return per-eye render recommendations");
    auto destroy = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance"));
    expect(destroy(instance) == XR_SUCCESS, "destroy queried instance");
}
void testGlesSessionSwapchainAndFrameLifecycle() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before session tests");
    XrInstance instance = createInstance(request.getInstanceProcAddr, true, true, true);
    auto getSystem = reinterpret_cast<PFN_xrGetSystem>(
        resolve(request.getInstanceProcAddr, instance, "xrGetSystem"));
    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    expect(getSystem(instance, &systemInfo, &system) == XR_SUCCESS,
           "get EndXR system for session");

    auto getRequirements = reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(
        resolve(request.getInstanceProcAddr, instance, "xrGetOpenGLESGraphicsRequirementsKHR"));
    XrGraphicsRequirementsOpenGLESKHR requirements{};
    requirements.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
    expect(getRequirements(instance, system, &requirements) == XR_SUCCESS,
           "query graphics requirements before creating session");
    auto createSession = reinterpret_cast<PFN_xrCreateSession>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateSession"));
    XrGraphicsBindingOpenGLESAndroidKHR binding{};
    binding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
    binding.display = reinterpret_cast<void*>(0x1000);
    binding.config = reinterpret_cast<void*>(0x2000);
    binding.context = reinterpret_cast<void*>(0x3000);
    XrSessionCreateInfo createInfo{};
    createInfo.type = XR_TYPE_SESSION_CREATE_INFO;
    createInfo.next = &binding;
    createInfo.systemId = system;
    XrSession session = XR_NULL_HANDLE;
    expect(createSession(instance, &createInfo, &session) == XR_SUCCESS &&
               session != XR_NULL_HANDLE,
           "create GLES-backed XR session");

    auto getBounds = reinterpret_cast<PFN_xrGetReferenceSpaceBoundsRect>(
        resolve(request.getInstanceProcAddr, instance, "xrGetReferenceSpaceBoundsRect"));
    XrExtent2Df bounds{9.0f, 7.0f};
    expect(getBounds(session, XR_REFERENCE_SPACE_TYPE_LOCAL, &bounds) ==
               XR_SPACE_BOUNDS_UNAVAILABLE &&
               bounds.width == 0.0f && bounds.height == 0.0f,
           "report unavailable physical bounds for the supported LOCAL space");
    bounds = {9.0f, 7.0f};
    expect(getBounds(session, XR_REFERENCE_SPACE_TYPE_VIEW, &bounds) ==
               XR_SPACE_BOUNDS_UNAVAILABLE &&
               bounds.width == 0.0f && bounds.height == 0.0f,
           "report unavailable physical bounds for the supported VIEW space");
    bounds = {9.0f, 7.0f};
    expect(getBounds(session, XR_REFERENCE_SPACE_TYPE_STAGE, &bounds) ==
               XR_SPACE_BOUNDS_UNAVAILABLE &&
               bounds.width == 0.0f && bounds.height == 0.0f,
           "accept STAGE with unavailable physical boundary extents");
    expect(getBounds(reinterpret_cast<XrSession>(static_cast<std::uintptr_t>(1)),
                     XR_REFERENCE_SPACE_TYPE_LOCAL, &bounds) == XR_ERROR_HANDLE_INVALID,
           "reject a stale session handle in the reference-space bounds query");
    expect(getBounds(session, XR_REFERENCE_SPACE_TYPE_LOCAL, nullptr) ==
               XR_ERROR_VALIDATION_FAILURE,
           "reject a null reference-space bounds output");

    auto pollEvent = reinterpret_cast<PFN_xrPollEvent>(
        resolve(request.getInstanceProcAddr, instance, "xrPollEvent"));
    XrEventDataBuffer event{};
    event.type = XR_TYPE_EVENT_DATA_BUFFER;
    expect(pollEvent(instance, &event) == XR_SUCCESS &&
               event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED &&
               reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state ==
                   XR_SESSION_STATE_READY,
           "session creation queues READY event");

    auto createSwapchain = reinterpret_cast<PFN_xrCreateSwapchain>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateSwapchain"));
    XrSwapchainCreateInfo swapInfo{};
    swapInfo.type = XR_TYPE_SWAPCHAIN_CREATE_INFO;
    swapInfo.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT |
                          XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
    swapInfo.format = 0x8058; // GL_RGBA8
    swapInfo.sampleCount = 1;
    swapInfo.width = 960;
    swapInfo.height = 1080;
    swapInfo.faceCount = 1;
    swapInfo.arraySize = 1;
    swapInfo.mipCount = 1;
    XrSwapchain left = XR_NULL_HANDLE;
    XrSwapchain right = XR_NULL_HANDLE;
    expect(createSwapchain(session, &swapInfo, &left) == XR_SUCCESS,
           "create left-eye GLES texture swapchain");
    expect(createSwapchain(session, &swapInfo, &right) == XR_SUCCESS,
           "create right-eye GLES texture swapchain");

    auto enumerateImages = reinterpret_cast<PFN_xrEnumerateSwapchainImages>(
        resolve(request.getInstanceProcAddr, instance, "xrEnumerateSwapchainImages"));
    std::uint32_t imageCount = 0;
    expect(enumerateImages(left, 0, &imageCount, nullptr) == XR_SUCCESS &&
               imageCount == 3,
           "report triple-buffered swapchain");
    XrSwapchainImageOpenGLESKHR images[3]{};
    for (auto& image : images) image.type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
    expect(enumerateImages(left, 3, &imageCount,
                           reinterpret_cast<XrSwapchainImageBaseHeader*>(images)) == XR_SUCCESS &&
               images[0].image != 0 && images[0].image != images[1].image,
           "expose distinct GL texture names");

    auto acquire = reinterpret_cast<PFN_xrAcquireSwapchainImage>(
        resolve(request.getInstanceProcAddr, instance, "xrAcquireSwapchainImage"));
    auto wait = reinterpret_cast<PFN_xrWaitSwapchainImage>(
        resolve(request.getInstanceProcAddr, instance, "xrWaitSwapchainImage"));
    auto release = reinterpret_cast<PFN_xrReleaseSwapchainImage>(
        resolve(request.getInstanceProcAddr, instance, "xrReleaseSwapchainImage"));
    XrSwapchainImageAcquireInfo acquireInfo{};
    acquireInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO;
    XrSwapchainImageWaitInfo waitInfo{};
    waitInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
    waitInfo.timeout = XR_INFINITE_DURATION;
    XrSwapchainImageReleaseInfo releaseInfo{};
    releaseInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;
    std::uint32_t imageIndex = 99;
    expect(acquire(left, &acquireInfo, &imageIndex) == XR_SUCCESS && imageIndex < 3,
           "acquire a swapchain image");
    expect(release(left, &releaseInfo) == XR_ERROR_CALL_ORDER_INVALID,
           "require waiting before image release");
    expect(wait(left, &waitInfo) == XR_SUCCESS, "wait for acquired swapchain image");
    expect(release(left, &releaseInfo) == XR_SUCCESS, "release rendered swapchain image");
    for (XrSwapchain swapchain : {right}) {
        expect(acquire(swapchain, &acquireInfo, &imageIndex) == XR_SUCCESS,
               "acquire right-eye image");
        expect(wait(swapchain, &waitInfo) == XR_SUCCESS, "wait right-eye image");
        expect(release(swapchain, &releaseInfo) == XR_SUCCESS, "release right-eye image");
    }

    auto beginSession = reinterpret_cast<PFN_xrBeginSession>(
        resolve(request.getInstanceProcAddr, instance, "xrBeginSession"));
    XrSessionBeginInfo beginInfo{};
    beginInfo.type = XR_TYPE_SESSION_BEGIN_INFO;
    beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    expect(beginSession(session, &beginInfo) == XR_SUCCESS, "begin XR session");
    for (const XrSessionState expected : {
             XR_SESSION_STATE_SYNCHRONIZED,
             XR_SESSION_STATE_VISIBLE,
             XR_SESSION_STATE_FOCUSED
         }) {
        event = {};
        expect(pollEvent(instance, &event) == XR_SUCCESS &&
                   reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state == expected,
               "session state transition event is queued");
    }

    auto createReferenceSpace = reinterpret_cast<PFN_xrCreateReferenceSpace>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateReferenceSpace"));
    XrReferenceSpaceCreateInfo referenceInfo{};
    referenceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    referenceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    referenceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    XrSpace localSpace = XR_NULL_HANDLE;
    expect(createReferenceSpace(session, &referenceInfo, &localSpace) == XR_SUCCESS,
           "create a local space for external head orientation tests");

    auto createHandTracker = reinterpret_cast<PFN_xrCreateHandTrackerEXT>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateHandTrackerEXT"));
    auto destroyHandTracker = reinterpret_cast<PFN_xrDestroyHandTrackerEXT>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyHandTrackerEXT"));
    auto locateHandJoints = reinterpret_cast<PFN_xrLocateHandJointsEXT>(
        resolve(request.getInstanceProcAddr, instance, "xrLocateHandJointsEXT"));
    XrHandTrackerCreateInfoEXT trackerInfo{};
    trackerInfo.type = XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT;
    trackerInfo.hand = XR_HAND_LEFT_EXT;
    trackerInfo.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
    XrHandTrackerEXT leftHandTracker = XR_NULL_HANDLE;
    expect(createHandTracker(session, &trackerInfo, &leftHandTracker) == XR_SUCCESS &&
               leftHandTracker != XR_NULL_HANDLE,
           "create left OpenXR hand tracker");
    trackerInfo.hand = XR_HAND_RIGHT_EXT;
    XrHandTrackerEXT rightHandTracker = XR_NULL_HANDLE;
    expect(createHandTracker(session, &trackerInfo, &rightHandTracker) == XR_SUCCESS &&
               rightHandTracker != XR_NULL_HANDLE,
           "create right OpenXR hand tracker");

    std::array<float, XR_HAND_JOINT_COUNT_EXT * 8> nativeJointSamples{};
    for (std::uint32_t joint = 0; joint < XR_HAND_JOINT_COUNT_EXT; ++joint) {
        const std::size_t offset = static_cast<std::size_t>(joint) * 8u;
        nativeJointSamples[offset] = 0.001f * static_cast<float>(joint);
        nativeJointSamples[offset + 1] = 0.01f;
        nativeJointSamples[offset + 2] = -0.005f;
        nativeJointSamples[offset + 3] = 0.009f;
        nativeJointSamples[offset + 7] = 1.0f;
    }
    endxrSetHandPose(0, 1, 0.25f, 0.1f, -1.5f, 0.0f, 0.0f, 0.0f, 1.0f);
    endxrSetHandJointData(0, 1, nativeJointSamples.data(),
        static_cast<std::uint32_t>(nativeJointSamples.size()));
    XrHandJointLocationEXT jointLocations[XR_HAND_JOINT_COUNT_EXT]{};
    XrHandJointVelocityEXT jointVelocities[XR_HAND_JOINT_COUNT_EXT]{};
    XrHandJointVelocitiesEXT velocityOutput{};
    velocityOutput.type = XR_TYPE_HAND_JOINT_VELOCITIES_EXT;
    velocityOutput.jointCount = XR_HAND_JOINT_COUNT_EXT;
    velocityOutput.jointVelocities = jointVelocities;
    XrHandJointLocationsEXT jointOutput{};
    jointOutput.type = XR_TYPE_HAND_JOINT_LOCATIONS_EXT;
    jointOutput.next = &velocityOutput;
    jointOutput.jointCount = XR_HAND_JOINT_COUNT_EXT;
    jointOutput.jointLocations = jointLocations;
    XrHandJointsLocateInfoEXT jointLocateInfo{};
    jointLocateInfo.type = XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT;
    jointLocateInfo.baseSpace = localSpace;
    jointLocateInfo.time = 1;
    endxrSetHeadOrientation(0.0f, 0.0f, 0.0f, 1.0f);
    expect(locateHandJoints(leftHandTracker, &jointLocateInfo, &jointOutput) == XR_SUCCESS &&
               jointOutput.isActive == XR_TRUE,
           "locate the current left hand joint set");
    expect((jointLocations[0].locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
               (jointLocations[0].locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0 &&
               (jointLocations[0].locationFlags & XR_SPACE_LOCATION_POSITION_TRACKED_BIT) == 0 &&
               std::abs(jointLocations[0].pose.position.x - 0.25f) < 0.001f &&
               std::abs(jointLocations[25].pose.position.x - 0.275f) < 0.001f &&
               jointVelocities[0].velocityFlags == 0 &&
               jointVelocities[0].linearVelocity.x == 0.0f &&
               jointVelocities[25].angularVelocity.z == 0.0f,
           "compose estimated joint poses and mark unsupported velocity estimates invalid");
    endxrClearHandPoses();
    expect(locateHandJoints(leftHandTracker, &jointLocateInfo, &jointOutput) == XR_SUCCESS &&
               jointOutput.isActive == XR_FALSE &&
               jointLocations[0].locationFlags == 0,
           "report inactive hand joints after camera tracking is cleared");
    expect(destroyHandTracker(leftHandTracker) == XR_SUCCESS &&
               destroyHandTracker(rightHandTracker) == XR_SUCCESS,
           "destroy both hand trackers before destroying the session");
    XrReferenceSpaceCreateInfo viewReferenceInfo{};
    viewReferenceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    viewReferenceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    viewReferenceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    XrSpace viewSpace = XR_NULL_HANDLE;
    expect(createReferenceSpace(session, &viewReferenceInfo, &viewSpace) == XR_SUCCESS,
           "create a VIEW reference space for stereo view tests");

    auto locateViews = reinterpret_cast<PFN_xrLocateViews>(
        resolve(request.getInstanceProcAddr, instance, "xrLocateViews"));
    XrViewLocateInfo locateInfo{};
    locateInfo.type = XR_TYPE_VIEW_LOCATE_INFO;
    locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    locateInfo.displayTime = 1;
    locateInfo.space = localSpace;
    XrViewState viewState{};
    viewState.type = XR_TYPE_VIEW_STATE;
    XrView locatedViews[2]{};
    for (auto& view : locatedViews) view.type = XR_TYPE_VIEW;
    std::uint32_t locatedViewCount = 0;

    endxrClearHeadOrientation();
    expect(locateViews(session, &locateInfo, &viewState, 2, &locatedViewCount,
                       locatedViews) == XR_SUCCESS &&
               locatedViewCount == 2 && viewState.viewStateFlags == 0 &&
               std::abs(locatedViews[0].pose.position.x + 0.032f) < 0.001f &&
               std::abs(locatedViews[1].pose.position.x - 0.032f) < 0.001f,
           "do not claim LOCAL view orientation before receiving a phone sensor sample");
    locateInfo.space = viewSpace;
    expect(locateViews(session, &locateInfo, &viewState, 2, &locatedViewCount,
                       locatedViews) == XR_SUCCESS &&
               viewState.viewStateFlags ==
                   (XR_VIEW_STATE_POSITION_VALID_BIT |
                    XR_VIEW_STATE_ORIENTATION_VALID_BIT) &&
               std::abs(locatedViews[0].pose.orientation.w - 1.0f) < 0.001f &&
               std::abs(locatedViews[0].pose.position.x + 0.032f) < 0.001f,
           "stereo views are fully located relative to VIEW without phone sensor data");
    locateInfo.space = localSpace;
    endxrSetHeadOrientation(0.0f, 0.0f, 0.70710678f, 0.70710678f);
    expect(locateViews(session, &locateInfo, &viewState, 2, &locatedViewCount,
                       locatedViews) == XR_SUCCESS &&
               (viewState.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) != 0 &&
               (viewState.viewStateFlags & XR_VIEW_STATE_ORIENTATION_TRACKED_BIT) != 0 &&
               std::abs(locatedViews[0].pose.orientation.z - 0.70710678f) < 0.001f &&
               std::abs(locatedViews[0].pose.orientation.w - 0.70710678f) < 0.001f,
           "apply phone rotation-vector quaternion to external stereo views");
    std::this_thread::sleep_for(std::chrono::milliseconds(550));
    expect(locateViews(session, &locateInfo, &viewState, 2, &locatedViewCount,
                       locatedViews) == XR_SUCCESS &&
               (viewState.viewStateFlags &
                   (XR_VIEW_STATE_ORIENTATION_VALID_BIT |
                    XR_VIEW_STATE_ORIENTATION_TRACKED_BIT)) == 0,
           "drop stale head-orientation flags after the native 500 ms timeout");
    endxrClearHeadOrientation();
    auto destroyViewSpace = reinterpret_cast<PFN_xrDestroySpace>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySpace"));
    expect(destroyViewSpace(viewSpace) == XR_SUCCESS,
           "destroy VIEW reference space used by stereo location tests");

    auto waitFrame = reinterpret_cast<PFN_xrWaitFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrWaitFrame"));
    auto beginFrame = reinterpret_cast<PFN_xrBeginFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrBeginFrame"));
    auto endFrame = reinterpret_cast<PFN_xrEndFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrEndFrame"));
    XrFrameWaitInfo frameWait{};
    frameWait.type = XR_TYPE_FRAME_WAIT_INFO;
    XrFrameState frameState{};
    frameState.type = XR_TYPE_FRAME_STATE;
    expect(waitFrame(session, &frameWait, &frameState) == XR_SUCCESS &&
               frameState.shouldRender == XR_TRUE && frameState.predictedDisplayTime > 0,
           "wait frame returns a renderable predicted time");
    expect(waitFrame(session, &frameWait, &frameState) == XR_ERROR_CALL_ORDER_INVALID,
           "reject a second wait before ending prior frame");
    XrFrameBeginInfo frameBegin{};
    frameBegin.type = XR_TYPE_FRAME_BEGIN_INFO;
    expect(beginFrame(session, &frameBegin) == XR_SUCCESS, "begin frame");
    expect(beginFrame(session, &frameBegin) == XR_ERROR_CALL_ORDER_INVALID,
           "reject double begin frame");
    XrCompositionLayerProjectionView views[2]{};
    for (auto& view : views) {
        view.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
        view.subImage.imageRect.extent = {960, 1080};
    }
    views[0].subImage.swapchain = left;
    views[1].subImage.swapchain = right;
    XrCompositionLayerProjection projection{};
    projection.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
    projection.viewCount = 2;
    projection.views = views;
    const XrCompositionLayerBaseHeader* layers[] = {
        reinterpret_cast<const XrCompositionLayerBaseHeader*>(&projection)
    };
    XrFrameEndInfo frameEnd{};
    frameEnd.type = XR_TYPE_FRAME_END_INFO;
    frameEnd.displayTime = frameState.predictedDisplayTime;
    frameEnd.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    frameEnd.layerCount = 1;
    frameEnd.layers = layers;
    expect(endFrame(session, &frameEnd) == XR_SUCCESS,
           "submit stereo projection layer to compositor");

    auto requestExit = reinterpret_cast<PFN_xrRequestExitSession>(
        resolve(request.getInstanceProcAddr, instance, "xrRequestExitSession"));
    auto endSession = reinterpret_cast<PFN_xrEndSession>(
        resolve(request.getInstanceProcAddr, instance, "xrEndSession"));
    expect(requestExit(session) == XR_SUCCESS, "request XR session exit");
    event = {};
    expect(pollEvent(instance, &event) == XR_SUCCESS &&
               reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state ==
                   XR_SESSION_STATE_STOPPING,
           "queue STOPPING event");
    expect(endSession(session) == XR_SUCCESS, "end XR session");
    event = {};
    expect(pollEvent(instance, &event) == XR_SUCCESS &&
               reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state ==
                   XR_SESSION_STATE_IDLE,
           "queue IDLE event");

    auto destroySpace = reinterpret_cast<PFN_xrDestroySpace>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySpace"));
    expect(destroySpace(localSpace) == XR_SUCCESS,
           "destroy local reference space after view orientation tests");
    auto destroySwapchain = reinterpret_cast<PFN_xrDestroySwapchain>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySwapchain"));
    expect(destroySwapchain(left) == XR_SUCCESS, "destroy left swapchain");
    expect(destroySwapchain(right) == XR_SUCCESS, "destroy right swapchain");
    auto destroySession = reinterpret_cast<PFN_xrDestroySession>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySession"));
    expect(destroySession(session) == XR_SUCCESS, "destroy XR session");
    auto destroyInstance = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance"));
    expect(destroyInstance(instance) == XR_SUCCESS, "destroy XR instance");
}
void testActionInputApiLifecycleAndNeutralState() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before action API tests");
    const XrInstance instance = createInstance(request.getInstanceProcAddr, true, true);

    auto stringToPath = reinterpret_cast<PFN_xrStringToPath>(
        resolve(request.getInstanceProcAddr, instance, "xrStringToPath"));
    auto pathToString = reinterpret_cast<PFN_xrPathToString>(
        resolve(request.getInstanceProcAddr, instance, "xrPathToString"));
    XrPath leftHand = XR_NULL_PATH;
    XrPath rightHand = XR_NULL_PATH;
    XrPath profile = XR_NULL_PATH;
    XrPath touchProfile = XR_NULL_PATH;
    XrPath leftSelect = XR_NULL_PATH;
    XrPath rightSelect = XR_NULL_PATH;
    XrPath leftTriggerValue = XR_NULL_PATH, rightTriggerValue = XR_NULL_PATH;
    XrPath leftThumbstick = XR_NULL_PATH, rightThumbstick = XR_NULL_PATH;
    XrPath leftGripPose = XR_NULL_PATH, rightGripPose = XR_NULL_PATH;
    expect(stringToPath(instance, "/user/hand/left", &leftHand) == XR_SUCCESS &&
               leftHand != XR_NULL_PATH,
           "intern left hand subaction path");
    expect(stringToPath(instance, "/user/hand/right", &rightHand) == XR_SUCCESS &&
               rightHand != XR_NULL_PATH && rightHand != leftHand,
           "intern right hand subaction path");
    expect(stringToPath(instance, "/interaction_profiles/khr/simple_controller",
                        &profile) == XR_SUCCESS,
           "intern simple-controller interaction profile");
    expect(stringToPath(instance, "/interaction_profiles/oculus/touch_controller",
                        &touchProfile) == XR_SUCCESS,
           "intern Oculus Touch interaction profile");
    expect(stringToPath(instance, "/user/hand/left/input/select/click",
                        &leftSelect) == XR_SUCCESS,
           "intern left select binding path");
    expect(stringToPath(instance, "/user/hand/right/input/select/click",
                        &rightSelect) == XR_SUCCESS,
           "intern right select binding path");
    expect(stringToPath(instance, "/user/hand/left/input/trigger/value", &leftTriggerValue) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/right/input/trigger/value", &rightTriggerValue) == XR_SUCCESS,
           "intern per-hand analog trigger paths");
    expect(stringToPath(instance, "/user/hand/left/input/thumbstick", &leftThumbstick) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/right/input/thumbstick", &rightThumbstick) == XR_SUCCESS,
           "intern per-hand thumbstick paths");
    expect(stringToPath(instance, "/user/hand/left/input/grip/pose", &leftGripPose) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/right/input/grip/pose", &rightGripPose) == XR_SUCCESS,
           "intern per-hand grip pose paths");
    XrPath leftHandAgain = XR_NULL_PATH;
    expect(stringToPath(instance, "/user/hand/left", &leftHandAgain) == XR_SUCCESS &&
               leftHandAgain == leftHand,
           "interning the same path returns the same handle");
    expect(stringToPath(instance, "/User/hand/left", &leftHandAgain) ==
               XR_ERROR_PATH_FORMAT_INVALID,
           "reject non-lowercase OpenXR paths");
    std::uint32_t pathLength = 0;
    expect(pathToString(instance, leftHand, 0, &pathLength, nullptr) == XR_SUCCESS &&
               pathLength == std::strlen("/user/hand/left") + 1,
           "query required path string length");
    char pathBuffer[XR_MAX_PATH_LENGTH]{};
    expect(pathToString(instance, leftHand, sizeof(pathBuffer), &pathLength, pathBuffer) ==
               XR_SUCCESS && std::strcmp(pathBuffer, "/user/hand/left") == 0,
           "convert path handle back to its string");

    auto createActionSet = reinterpret_cast<PFN_xrCreateActionSet>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateActionSet"));
    auto destroyActionSet = reinterpret_cast<PFN_xrDestroyActionSet>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyActionSet"));
    auto createAction = reinterpret_cast<PFN_xrCreateAction>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateAction"));
    auto destroyAction = reinterpret_cast<PFN_xrDestroyAction>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyAction"));

    XrActionSetCreateInfo setInfo{};
    setInfo.type = XR_TYPE_ACTION_SET_CREATE_INFO;
    std::strncpy(setInfo.actionSetName, "gameplay", sizeof(setInfo.actionSetName) - 1);
    std::strncpy(setInfo.localizedActionSetName, "Gameplay", sizeof(setInfo.localizedActionSetName) - 1);
    setInfo.priority = 0;
    XrActionSet actionSet = XR_NULL_HANDLE;
    expect(createActionSet(instance, &setInfo, &actionSet) == XR_SUCCESS &&
               actionSet != XR_NULL_HANDLE,
           "create gameplay action set");

    const XrPath handPaths[] = {leftHand, rightHand};
    XrActionCreateInfo triggerInfo{};
    triggerInfo.type = XR_TYPE_ACTION_CREATE_INFO;
    std::strncpy(triggerInfo.actionName, "select", sizeof(triggerInfo.actionName) - 1);
    std::strncpy(triggerInfo.localizedActionName, "Select", sizeof(triggerInfo.localizedActionName) - 1);
    triggerInfo.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
    triggerInfo.countSubactionPaths = 2;
    triggerInfo.subactionPaths = handPaths;
    XrAction triggerAction = XR_NULL_HANDLE;
    expect(createAction(actionSet, &triggerInfo, &triggerAction) == XR_SUCCESS,
           "create boolean trigger action");

    XrActionCreateInfo poseInfo{};
    poseInfo.type = XR_TYPE_ACTION_CREATE_INFO;
    std::strncpy(poseInfo.actionName, "hand_pose", sizeof(poseInfo.actionName) - 1);
    std::strncpy(poseInfo.localizedActionName, "Hand Pose", sizeof(poseInfo.localizedActionName) - 1);
    poseInfo.actionType = XR_ACTION_TYPE_POSE_INPUT;
    poseInfo.countSubactionPaths = 2;
    poseInfo.subactionPaths = handPaths;
    XrAction poseAction = XR_NULL_HANDLE;
    expect(createAction(actionSet, &poseInfo, &poseAction) == XR_SUCCESS,
           "create controller pose action");

    XrActionCreateInfo hapticInfo{};
    hapticInfo.type = XR_TYPE_ACTION_CREATE_INFO;
    std::strncpy(hapticInfo.actionName, "haptic", sizeof(hapticInfo.actionName) - 1);
    std::strncpy(hapticInfo.localizedActionName, "Haptic", sizeof(hapticInfo.localizedActionName) - 1);
    hapticInfo.actionType = XR_ACTION_TYPE_VIBRATION_OUTPUT;
    hapticInfo.countSubactionPaths = 2;
    hapticInfo.subactionPaths = handPaths;
    XrAction hapticAction = XR_NULL_HANDLE;
    expect(createAction(actionSet, &hapticInfo, &hapticAction) == XR_SUCCESS,
           "create haptic output action");

    XrActionCreateInfo triggerValueInfo{};
    triggerValueInfo.type = XR_TYPE_ACTION_CREATE_INFO;
    std::strncpy(triggerValueInfo.actionName, "trigger_value", sizeof(triggerValueInfo.actionName) - 1);
    std::strncpy(triggerValueInfo.localizedActionName, "Trigger Value", sizeof(triggerValueInfo.localizedActionName) - 1);
    triggerValueInfo.actionType = XR_ACTION_TYPE_FLOAT_INPUT;
    triggerValueInfo.countSubactionPaths = 2;
    triggerValueInfo.subactionPaths = handPaths;
    XrAction triggerValueAction = XR_NULL_HANDLE;
    expect(createAction(actionSet, &triggerValueInfo, &triggerValueAction) == XR_SUCCESS,
           "create analog trigger action");

    XrActionCreateInfo stickInfo{};
    stickInfo.type = XR_TYPE_ACTION_CREATE_INFO;
    std::strncpy(stickInfo.actionName, "move_stick", sizeof(stickInfo.actionName) - 1);
    std::strncpy(stickInfo.localizedActionName, "Move Stick", sizeof(stickInfo.localizedActionName) - 1);
    stickInfo.actionType = XR_ACTION_TYPE_VECTOR2F_INPUT;
    stickInfo.countSubactionPaths = 2;
    stickInfo.subactionPaths = handPaths;
    XrAction stickAction = XR_NULL_HANDLE;
    expect(createAction(actionSet, &stickInfo, &stickAction) == XR_SUCCESS,
           "create thumbstick vector action");

    auto suggestBindings = reinterpret_cast<PFN_xrSuggestInteractionProfileBindings>(
        resolve(request.getInstanceProcAddr, instance, "xrSuggestInteractionProfileBindings"));
    const XrActionSuggestedBinding bindings[] = {
        {triggerAction, leftSelect}, {triggerAction, rightSelect},
        {triggerValueAction, leftTriggerValue}, {triggerValueAction, rightTriggerValue},
        {stickAction, leftThumbstick}, {stickAction, rightThumbstick},
        {poseAction, leftGripPose}, {poseAction, rightGripPose}
    };
    XrInteractionProfileSuggestedBinding suggested{};
    suggested.type = XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING;
    suggested.interactionProfile = profile;
    suggested.countSuggestedBindings = 8;
    suggested.suggestedBindings = bindings;
    expect(suggestBindings(instance, &suggested) == XR_SUCCESS,
           "accept validated simple-controller interaction-profile bindings");
    suggested.interactionProfile = touchProfile;
    expect(suggestBindings(instance, &suggested) == XR_SUCCESS,
           "accept validated Touch interaction-profile bindings");

    auto getSystem = reinterpret_cast<PFN_xrGetSystem>(
        resolve(request.getInstanceProcAddr, instance, "xrGetSystem"));
    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    expect(getSystem(instance, &systemInfo, &system) == XR_SUCCESS,
           "get HMD system for action session");

    auto getRequirements = reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(
        resolve(request.getInstanceProcAddr, instance, "xrGetOpenGLESGraphicsRequirementsKHR"));
    XrGraphicsRequirementsOpenGLESKHR requirements{};
    requirements.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
    expect(getRequirements(instance, system, &requirements) == XR_SUCCESS,
           "query GLES requirements for action session");

    auto createSession = reinterpret_cast<PFN_xrCreateSession>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateSession"));
    XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding{};
    graphicsBinding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
    graphicsBinding.display = reinterpret_cast<void*>(0x1000);
    graphicsBinding.config = reinterpret_cast<void*>(0x2000);
    graphicsBinding.context = reinterpret_cast<void*>(0x3000);
    XrSessionCreateInfo sessionInfo{};
    sessionInfo.type = XR_TYPE_SESSION_CREATE_INFO;
    sessionInfo.next = &graphicsBinding;
    sessionInfo.systemId = system;
    XrSession session = XR_NULL_HANDLE;
    expect(createSession(instance, &sessionInfo, &session) == XR_SUCCESS,
           "create GLES session for action tests");

    auto attachActionSets = reinterpret_cast<PFN_xrAttachSessionActionSets>(
        resolve(request.getInstanceProcAddr, instance, "xrAttachSessionActionSets"));
    XrSessionActionSetsAttachInfo attachInfo{};
    attachInfo.type = XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO;
    attachInfo.countActionSets = 1;
    attachInfo.actionSets = &actionSet;
    expect(attachActionSets(session, &attachInfo) == XR_SUCCESS,
           "attach gameplay action set to session");
    expect(attachActionSets(session, &attachInfo) == XR_ERROR_ACTIONSETS_ALREADY_ATTACHED,
           "reject a second action-set attachment");

    auto beginSession = reinterpret_cast<PFN_xrBeginSession>(
        resolve(request.getInstanceProcAddr, instance, "xrBeginSession"));
    XrSessionBeginInfo beginInfo{};
    beginInfo.type = XR_TYPE_SESSION_BEGIN_INFO;
    beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    expect(beginSession(session, &beginInfo) == XR_SUCCESS,
           "begin session before synchronizing actions");

    auto syncActions = reinterpret_cast<PFN_xrSyncActions>(
        resolve(request.getInstanceProcAddr, instance, "xrSyncActions"));
    XrActiveActionSet activeSet{actionSet, XR_NULL_PATH};
    XrActionsSyncInfo syncInfo{};
    syncInfo.type = XR_TYPE_ACTIONS_SYNC_INFO;
    syncInfo.countActiveActionSets = 1;
    syncInfo.activeActionSets = &activeSet;
    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "synchronize attached action sets");

    auto getBoolean = reinterpret_cast<PFN_xrGetActionStateBoolean>(
        resolve(request.getInstanceProcAddr, instance, "xrGetActionStateBoolean"));
    XrActionStateGetInfo stateInfo{};
    stateInfo.type = XR_TYPE_ACTION_STATE_GET_INFO;
    stateInfo.action = triggerAction;
    stateInfo.subactionPath = leftHand;
    XrActionStateBoolean booleanState{};
    booleanState.type = XR_TYPE_ACTION_STATE_BOOLEAN;
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_FALSE &&
               booleanState.isActive == XR_FALSE,
           "unfed physical input remains neutral and inactive");
    endxrSetControllerInput(0, 1, 0.91f, 0.2f, -0.35f, 0.6f, 0);
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_FALSE && booleanState.isActive == XR_FALSE &&
               booleanState.changedSinceLastSync == XR_FALSE,
           "input remains frozen until the next xrSyncActions");
    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "capture injected input at the next action synchronization");
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_TRUE && booleanState.isActive == XR_TRUE &&
               booleanState.changedSinceLastSync == XR_TRUE && booleanState.lastChangeTime > 0,
           "left select action changes on the next synchronized input snapshot");
    const XrTime selectChangeTime = booleanState.lastChangeTime;
    stateInfo.subactionPath = rightHand;
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_FALSE && booleanState.isActive == XR_FALSE,
           "left controller input does not leak into right action");
    stateInfo.subactionPath = leftHand;

    auto getFloat = reinterpret_cast<PFN_xrGetActionStateFloat>(
        resolve(request.getInstanceProcAddr, instance, "xrGetActionStateFloat"));
    XrActionStateFloat floatState{};
    floatState.type = XR_TYPE_ACTION_STATE_FLOAT;
    expect(getFloat(session, &stateInfo, &floatState) == XR_ERROR_ACTION_TYPE_MISMATCH,
           "reject reading a boolean action as float");
    XrActionStateGetInfo triggerValueStateInfo{};
    triggerValueStateInfo.type = XR_TYPE_ACTION_STATE_GET_INFO;
    triggerValueStateInfo.action = triggerValueAction;
    triggerValueStateInfo.subactionPath = leftHand;
    floatState = {};
    floatState.type = XR_TYPE_ACTION_STATE_FLOAT;
    expect(getFloat(session, &triggerValueStateInfo, &floatState) == XR_SUCCESS &&
               floatState.isActive == XR_TRUE && std::abs(floatState.currentState - 0.91f) < 0.001f &&
               floatState.changedSinceLastSync == XR_TRUE && floatState.lastChangeTime > 0,
           "float action receives analog left trigger value and reports the sync transition");
    const XrTime triggerValueChangeTime = floatState.lastChangeTime;
    triggerValueStateInfo.subactionPath = rightHand;
    expect(getFloat(session, &triggerValueStateInfo, &floatState) == XR_SUCCESS &&
               floatState.isActive == XR_FALSE && floatState.currentState == 0.0f,
           "analog trigger value stays isolated to left hand");
    auto getVector = reinterpret_cast<PFN_xrGetActionStateVector2f>(
        resolve(request.getInstanceProcAddr, instance, "xrGetActionStateVector2f"));
    XrActionStateGetInfo stickStateInfo{};
    stickStateInfo.type = XR_TYPE_ACTION_STATE_GET_INFO;
    stickStateInfo.action = stickAction;
    stickStateInfo.subactionPath = leftHand;
    XrActionStateVector2f stickState{};
    stickState.type = XR_TYPE_ACTION_STATE_VECTOR2F;
    expect(getVector(session, &stickStateInfo, &stickState) == XR_SUCCESS &&
               stickState.isActive == XR_TRUE &&
               std::abs(stickState.currentState.x + 0.35f) < 0.001f &&
               std::abs(stickState.currentState.y - 0.6f) < 0.001f &&
               stickState.changedSinceLastSync == XR_TRUE && stickState.lastChangeTime > 0,
           "Vector2 action receives both left thumbstick axes and reports the sync transition");
    const XrTime stickChangeTime = stickState.lastChangeTime;
    stickStateInfo.subactionPath = rightHand;
    expect(getVector(session, &stickStateInfo, &stickState) == XR_SUCCESS &&
               stickState.isActive == XR_FALSE &&
               stickState.currentState.x == 0.0f && stickState.currentState.y == 0.0f,
           "thumbstick vector stays isolated to left hand");

    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "capture an unchanged controller sample on the following sync");
    stateInfo.subactionPath = leftHand;
    triggerValueStateInfo.subactionPath = leftHand;
    stickStateInfo.subactionPath = leftHand;
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_TRUE &&
               booleanState.changedSinceLastSync == XR_FALSE &&
               booleanState.lastChangeTime == selectChangeTime,
           "unchanged boolean clears changedSinceLastSync and preserves lastChangeTime");
    expect(getFloat(session, &triggerValueStateInfo, &floatState) == XR_SUCCESS &&
               floatState.changedSinceLastSync == XR_FALSE &&
               floatState.lastChangeTime == triggerValueChangeTime,
           "unchanged float clears changedSinceLastSync and preserves lastChangeTime");
    expect(getVector(session, &stickStateInfo, &stickState) == XR_SUCCESS &&
               stickState.changedSinceLastSync == XR_FALSE &&
               stickState.lastChangeTime == stickChangeTime,
           "unchanged Vector2 clears changedSinceLastSync and preserves lastChangeTime");

    auto getPose = reinterpret_cast<PFN_xrGetActionStatePose>(
        resolve(request.getInstanceProcAddr, instance, "xrGetActionStatePose"));
    XrActionStateGetInfo poseStateInfo{};
    poseStateInfo.type = XR_TYPE_ACTION_STATE_GET_INFO;
    poseStateInfo.action = poseAction;
    poseStateInfo.subactionPath = leftHand;
    XrActionStatePose poseState{};
    poseState.type = XR_TYPE_ACTION_STATE_POSE;
    expect(getPose(session, &poseStateInfo, &poseState) == XR_SUCCESS &&
               poseState.isActive == XR_FALSE,
           "unfed controller pose action remains inactive");

    auto currentProfile = reinterpret_cast<PFN_xrGetCurrentInteractionProfile>(
        resolve(request.getInstanceProcAddr, instance, "xrGetCurrentInteractionProfile"));
    XrInteractionProfileState profileState{};
    profileState.type = XR_TYPE_INTERACTION_PROFILE_STATE;
    expect(currentProfile(session, leftHand, &profileState) == XR_SUCCESS &&
               profileState.interactionProfile == touchProfile,
           "prefer Quest Touch profile when Touch and simple-controller bindings are both suggested");
    expect(currentProfile(session, rightHand, &profileState) == XR_SUCCESS &&
               profileState.interactionProfile == XR_NULL_PATH,
           "right interaction profile remains inactive without right input");

    auto enumerateBoundSources = reinterpret_cast<PFN_xrEnumerateBoundSourcesForAction>(
        resolve(request.getInstanceProcAddr, instance, "xrEnumerateBoundSourcesForAction"));
    XrBoundSourcesForActionEnumerateInfo boundInfo{};
    boundInfo.type = XR_TYPE_BOUND_SOURCES_FOR_ACTION_ENUMERATE_INFO;
    boundInfo.action = triggerAction;
    std::uint32_t sourceCount = 99;
    expect(enumerateBoundSources(session, &boundInfo, 0, &sourceCount, nullptr) ==
               XR_SUCCESS && sourceCount == 1,
           "query active bound-source count after input injection");
    XrPath activeSource = XR_NULL_PATH;
    expect(enumerateBoundSources(session, &boundInfo, 1, &sourceCount, &activeSource) ==
               XR_SUCCESS && sourceCount == 1 && activeSource == leftSelect,
           "return active left select source path");

    endxrSetControllerInput(0, 1, 0.10f, 0.2f, -0.35f, 0.6f, 0);
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_TRUE &&
               booleanState.changedSinceLastSync == XR_FALSE &&
               booleanState.lastChangeTime == selectChangeTime,
           "new physical values do not mutate the already synchronized snapshot");
    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "synchronize released trigger state");
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_FALSE && booleanState.isActive == XR_TRUE &&
               booleanState.changedSinceLastSync == XR_TRUE &&
               booleanState.lastChangeTime > selectChangeTime,
           "trigger release is reported as a state change on sync");
    endxrClearControllerInput();
    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "synchronize controller disconnect");
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_FALSE && booleanState.isActive == XR_FALSE &&
               booleanState.changedSinceLastSync == XR_TRUE,
           "controller disconnect changes the input active state");

    auto getLocalizedName = reinterpret_cast<PFN_xrGetInputSourceLocalizedName>(
        resolve(request.getInstanceProcAddr, instance, "xrGetInputSourceLocalizedName"));
    XrInputSourceLocalizedNameGetInfo localizedInfo{};
    localizedInfo.type = XR_TYPE_INPUT_SOURCE_LOCALIZED_NAME_GET_INFO;
    localizedInfo.sourcePath = leftHand;
    localizedInfo.whichComponents = XR_INPUT_SOURCE_LOCALIZED_NAME_USER_PATH_BIT;
    std::uint32_t localizedLength = 0;
    expect(getLocalizedName(session, &localizedInfo, 0, &localizedLength, nullptr) == XR_SUCCESS &&
               localizedLength == std::strlen("/user/hand/left") + 1,
           "report localized input-source name length");

    // Exercise a full press -> hold -> release -> press cycle. The Action API
    // must only publish transitions at xrSyncActions, and a release must not
    // leave a stale pressed button in the next snapshot.
    endxrSetControllerInput(0, 1, 0.0f, 0.0f, 0.0f, 0.0f, 0);
    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "synchronize trigger release sample");
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_FALSE &&
               booleanState.isActive == XR_TRUE &&
               booleanState.changedSinceLastSync == XR_TRUE &&
               booleanState.lastChangeTime >= selectChangeTime,
           "trigger release produces one boolean transition");
    expect(getFloat(session, &triggerValueStateInfo, &floatState) == XR_SUCCESS &&
               floatState.currentState == 0.0f &&
               floatState.isActive == XR_TRUE &&
               floatState.changedSinceLastSync == XR_TRUE &&
               floatState.lastChangeTime >= triggerValueChangeTime,
           "analog trigger release produces a float transition to zero");
    expect(getVector(session, &stickStateInfo, &stickState) == XR_SUCCESS &&
               stickState.currentState.x == 0.0f &&
               stickState.currentState.y == 0.0f &&
               stickState.changedSinceLastSync == XR_TRUE &&
               stickState.lastChangeTime >= stickChangeTime,
           "thumbstick recenter produces a Vector2 transition to zero");

    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "synchronize unchanged released trigger a second time");
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.changedSinceLastSync == XR_FALSE,
           "held released trigger does not report a duplicate transition");
    expect(getFloat(session, &triggerValueStateInfo, &floatState) == XR_SUCCESS &&
               floatState.changedSinceLastSync == XR_FALSE,
           "unchanged released analog value does not report a duplicate transition");

    endxrClearControllerInput();
    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "synchronize controller disconnect");
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_FALSE &&
               booleanState.isActive == XR_FALSE &&
               booleanState.changedSinceLastSync == XR_TRUE,
           "controller disconnect changes an active action to inactive");
    expect(getFloat(session, &triggerValueStateInfo, &floatState) == XR_SUCCESS &&
               floatState.currentState == 0.0f &&
               floatState.isActive == XR_FALSE &&
               floatState.changedSinceLastSync == XR_TRUE,
           "controller disconnect deactivates analog input");

    endxrSetControllerInput(0, 1, 0.91f, 0.2f, -0.35f, 0.6f, 0);
    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "synchronize controller reconnect and press");
    expect(getBoolean(session, &stateInfo, &booleanState) == XR_SUCCESS &&
               booleanState.currentState == XR_TRUE &&
               booleanState.isActive == XR_TRUE &&
               booleanState.changedSinceLastSync == XR_TRUE,
           "controller reconnect reports a fresh press transition");

    auto createActionSpace = reinterpret_cast<PFN_xrCreateActionSpace>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateActionSpace"));
    XrActionSpaceCreateInfo actionSpaceInfo{};
    actionSpaceInfo.type = XR_TYPE_ACTION_SPACE_CREATE_INFO;
    actionSpaceInfo.action = poseAction;
    actionSpaceInfo.subactionPath = leftHand;
    actionSpaceInfo.poseInActionSpace.orientation.w = 1.0f;
    XrSpace actionSpace = XR_NULL_HANDLE;
    expect(createActionSpace(session, &actionSpaceInfo, &actionSpace) == XR_SUCCESS,
           "create pose action space");

    auto createReferenceSpace = reinterpret_cast<PFN_xrCreateReferenceSpace>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateReferenceSpace"));
    XrReferenceSpaceCreateInfo referenceInfo{};
    referenceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    referenceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    referenceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    XrSpace localSpace = XR_NULL_HANDLE;
    expect(createReferenceSpace(session, &referenceInfo, &localSpace) == XR_SUCCESS,
           "create local reference space");
    auto locateSpace = reinterpret_cast<PFN_xrLocateSpace>(
        resolve(request.getInstanceProcAddr, instance, "xrLocateSpace"));
    XrSpaceLocation location{};
    location.type = XR_TYPE_SPACE_LOCATION;
    expect(locateSpace(actionSpace, localSpace, 1, &location) == XR_SUCCESS &&
               location.locationFlags == 0,
           "do not invent a controller pose when no input source is available");

    endxrSetHeadOrientation(0.0f, 0.0f, 0.0f, 1.0f);
    endxrSetHandPose(0, 1, 0.30f, 0.20f, -1.80f, 0.0f, 0.0f, 0.0f, 1.0f);
    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "synchronize estimated left palm pose");
    expect(getPose(session, &poseStateInfo, &poseState) == XR_SUCCESS &&
               poseState.isActive == XR_TRUE,
           "activate grip pose action from an estimated hand pose");
    expect(locateSpace(actionSpace, localSpace, 1, &location) == XR_SUCCESS &&
               location.locationFlags ==
                   (XR_SPACE_LOCATION_POSITION_VALID_BIT |
                    XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) &&
               std::abs(location.pose.position.x - 0.30f) < 0.001f &&
               std::abs(location.pose.position.y - 0.20f) < 0.001f &&
               std::abs(location.pose.position.z + 1.80f) < 0.001f &&
               std::abs(location.pose.orientation.w - 1.0f) < 0.001f,
           "locate approximate palm pose without claiming tracked 6DoF");
    endxrClearHandPoses();
    expect(syncActions(session, &syncInfo) == XR_SUCCESS &&
               getPose(session, &poseStateInfo, &poseState) == XR_SUCCESS &&
               poseState.isActive == XR_FALSE &&
               locateSpace(actionSpace, localSpace, 1, &location) == XR_SUCCESS &&
               location.locationFlags == 0,
           "clearing estimated hand pose deactivates grip pose and its space");

    XrReferenceSpaceCreateInfo viewReferenceInfo{};
    viewReferenceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    viewReferenceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    viewReferenceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    XrSpace viewSpace = XR_NULL_HANDLE;
    expect(createReferenceSpace(session, &viewReferenceInfo, &viewSpace) == XR_SUCCESS,
           "create a VIEW reference space for relative-location tests");

    endxrClearHeadOrientation();
    expect(locateSpace(localSpace, localSpace, 1, &location) == XR_SUCCESS &&
               location.locationFlags ==
                   (XR_SPACE_LOCATION_POSITION_VALID_BIT |
                    XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) &&
               std::abs(location.pose.position.x) < 0.001f &&
               std::abs(location.pose.position.y) < 0.001f &&
               std::abs(location.pose.position.z) < 0.001f &&
               std::abs(location.pose.orientation.w - 1.0f) < 0.001f,
           "a reference space located relative to itself is identity without sensor data");
    expect(locateSpace(viewSpace, viewSpace, 1, &location) == XR_SUCCESS &&
               location.locationFlags ==
                   (XR_SPACE_LOCATION_POSITION_VALID_BIT |
                    XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) &&
               std::abs(location.pose.orientation.w - 1.0f) < 0.001f,
           "VIEW located relative to itself is identity without sensor data");
    expect(locateSpace(viewSpace, localSpace, 1, &location) == XR_SUCCESS &&
               location.locationFlags == 0,
           "VIEW to LOCAL needs a fresh head-orientation sample");

    endxrSetHeadOrientation(0.0f, 0.0f, 0.70710678f, 0.70710678f);
    expect(locateSpace(viewSpace, localSpace, 1, &location) == XR_SUCCESS &&
               location.locationFlags ==
                   (XR_SPACE_LOCATION_ORIENTATION_VALID_BIT |
                    XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT) &&
               std::abs(location.pose.orientation.z - 0.70710678f) < 0.001f &&
               std::abs(location.pose.orientation.w - 0.70710678f) < 0.001f,
           "locate VIEW relative to LOCAL using the normalized phone orientation");
    expect(locateSpace(localSpace, viewSpace, 1, &location) == XR_SUCCESS &&
               location.locationFlags ==
                   (XR_SPACE_LOCATION_ORIENTATION_VALID_BIT |
                    XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT) &&
               std::abs(location.pose.orientation.z + 0.70710678f) < 0.001f &&
               std::abs(location.pose.orientation.w - 0.70710678f) < 0.001f,
           "locate LOCAL relative to VIEW using the inverse phone orientation");

    endxrClearHeadOrientation();
    expect(locateSpace(localSpace, localSpace, 1, &location) == XR_SUCCESS &&
               location.locationFlags ==
                   (XR_SPACE_LOCATION_POSITION_VALID_BIT |
                    XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) &&
               std::abs(location.pose.orientation.w - 1.0f) < 0.001f,
           "same-reference-space locations remain valid when the phone sensor goes stale");
    expect(locateSpace(viewSpace, localSpace, 1, &location) == XR_SUCCESS &&
               location.locationFlags == 0,
           "cross-reference-space locations lose tracked flags when the sensor goes stale");

    auto applyHaptic = reinterpret_cast<PFN_xrApplyHapticFeedback>(
        resolve(request.getInstanceProcAddr, instance, "xrApplyHapticFeedback"));
    XrHapticActionInfo hapticActionInfo{};
    hapticActionInfo.type = XR_TYPE_HAPTIC_ACTION_INFO;
    hapticActionInfo.action = hapticAction;
    hapticActionInfo.subactionPath = leftHand;
    XrHapticVibration vibration{};
    vibration.type = XR_TYPE_HAPTIC_VIBRATION;
    vibration.duration = XR_MIN_HAPTIC_DURATION;
    vibration.frequency = XR_FREQUENCY_UNSPECIFIED;
    vibration.amplitude = 1.0f;
    expect(applyHaptic(session, &hapticActionInfo,
                       reinterpret_cast<const XrHapticBaseHeader*>(&vibration)) ==
               XR_ERROR_FEATURE_UNSUPPORTED,
           "report unsupported hardware haptics honestly");

    auto destroySpace = reinterpret_cast<PFN_xrDestroySpace>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySpace"));
    expect(destroySpace(actionSpace) == XR_SUCCESS, "destroy action space");
    expect(destroySpace(viewSpace) == XR_SUCCESS, "destroy VIEW reference space");
    expect(destroySpace(localSpace) == XR_SUCCESS, "destroy local reference space");

    auto requestExit = reinterpret_cast<PFN_xrRequestExitSession>(
        resolve(request.getInstanceProcAddr, instance, "xrRequestExitSession"));
    auto endSession = reinterpret_cast<PFN_xrEndSession>(
        resolve(request.getInstanceProcAddr, instance, "xrEndSession"));
    expect(requestExit(session) == XR_SUCCESS, "request action test session exit");
    expect(endSession(session) == XR_SUCCESS, "end action test session");
    auto destroySession = reinterpret_cast<PFN_xrDestroySession>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySession"));
    expect(destroySession(session) == XR_SUCCESS, "destroy action test session");

    endxrClearControllerInput();
    expect(destroyAction(triggerAction) == XR_SUCCESS, "destroy select action");
    expect(destroyAction(triggerValueAction) == XR_SUCCESS, "destroy analog trigger action");
    expect(destroyAction(stickAction) == XR_SUCCESS, "destroy thumbstick action");
    expect(destroyAction(poseAction) == XR_SUCCESS, "destroy pose action");
    expect(destroyAction(hapticAction) == XR_SUCCESS, "destroy haptic action");
    expect(destroyActionSet(actionSet) == XR_SUCCESS, "destroy gameplay action set");
    auto destroyInstance = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance"));
    expect(destroyInstance(instance) == XR_SUCCESS, "destroy action test instance");
}

void testHandInteractionProfileMapsCameraGesturesAndJointPoses() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before hand-interaction profile test");
    const XrInstance instance = createInstance(
        request.getInstanceProcAddr, true, true, true, true);

    auto stringToPath = reinterpret_cast<PFN_xrStringToPath>(
        resolve(request.getInstanceProcAddr, instance, "xrStringToPath"));
    auto destroyInstance = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance"));
    auto createActionSet = reinterpret_cast<PFN_xrCreateActionSet>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateActionSet"));
    auto destroyActionSet = reinterpret_cast<PFN_xrDestroyActionSet>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyActionSet"));
    auto createAction = reinterpret_cast<PFN_xrCreateAction>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateAction"));
    auto destroyAction = reinterpret_cast<PFN_xrDestroyAction>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyAction"));
    auto suggestBindings = reinterpret_cast<PFN_xrSuggestInteractionProfileBindings>(
        resolve(request.getInstanceProcAddr, instance, "xrSuggestInteractionProfileBindings"));
    auto getSystem = reinterpret_cast<PFN_xrGetSystem>(
        resolve(request.getInstanceProcAddr, instance, "xrGetSystem"));
    auto getRequirements = reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(
        resolve(request.getInstanceProcAddr, instance, "xrGetOpenGLESGraphicsRequirementsKHR"));
    auto createSession = reinterpret_cast<PFN_xrCreateSession>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateSession"));
    auto destroySession = reinterpret_cast<PFN_xrDestroySession>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySession"));
    auto attachActionSets = reinterpret_cast<PFN_xrAttachSessionActionSets>(
        resolve(request.getInstanceProcAddr, instance, "xrAttachSessionActionSets"));
    auto beginSession = reinterpret_cast<PFN_xrBeginSession>(
        resolve(request.getInstanceProcAddr, instance, "xrBeginSession"));
    auto requestExitSession = reinterpret_cast<PFN_xrRequestExitSession>(
        resolve(request.getInstanceProcAddr, instance, "xrRequestExitSession"));
    auto endSession = reinterpret_cast<PFN_xrEndSession>(
        resolve(request.getInstanceProcAddr, instance, "xrEndSession"));
    auto syncActions = reinterpret_cast<PFN_xrSyncActions>(
        resolve(request.getInstanceProcAddr, instance, "xrSyncActions"));
    auto getFloat = reinterpret_cast<PFN_xrGetActionStateFloat>(
        resolve(request.getInstanceProcAddr, instance, "xrGetActionStateFloat"));
    auto getBoolean = reinterpret_cast<PFN_xrGetActionStateBoolean>(
        resolve(request.getInstanceProcAddr, instance, "xrGetActionStateBoolean"));
    auto getPose = reinterpret_cast<PFN_xrGetActionStatePose>(
        resolve(request.getInstanceProcAddr, instance, "xrGetActionStatePose"));
    auto currentProfile = reinterpret_cast<PFN_xrGetCurrentInteractionProfile>(
        resolve(request.getInstanceProcAddr, instance, "xrGetCurrentInteractionProfile"));
    auto createReferenceSpace = reinterpret_cast<PFN_xrCreateReferenceSpace>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateReferenceSpace"));
    auto createActionSpace = reinterpret_cast<PFN_xrCreateActionSpace>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateActionSpace"));
    auto locateSpace = reinterpret_cast<PFN_xrLocateSpace>(
        resolve(request.getInstanceProcAddr, instance, "xrLocateSpace"));
    auto destroySpace = reinterpret_cast<PFN_xrDestroySpace>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySpace"));

    XrPath leftHand = XR_NULL_PATH;
    XrPath rightHand = XR_NULL_PATH;
    XrPath handProfile = XR_NULL_PATH;
    XrPath touchProfile = XR_NULL_PATH;
    XrPath touchPinchPosePath = XR_NULL_PATH;
    XrPath touchPokePosePath = XR_NULL_PATH;
    XrPath aimPosePath = XR_NULL_PATH;
    XrPath pinchValuePath = XR_NULL_PATH;
    XrPath pinchReadyPath = XR_NULL_PATH;
    XrPath graspValuePath = XR_NULL_PATH;
    XrPath graspReadyPath = XR_NULL_PATH;
    XrPath aimActivatePath = XR_NULL_PATH;
    XrPath aimActivateReadyPath = XR_NULL_PATH;
    XrPath pinchPosePath = XR_NULL_PATH;
    XrPath pokePosePath = XR_NULL_PATH;
    expect(stringToPath(instance, "/user/hand/left", &leftHand) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/right", &rightHand) == XR_SUCCESS,
           "intern hand-interaction subaction paths");
    expect(stringToPath(instance, "/interaction_profiles/ext/hand_interaction_ext",
                        &handProfile) == XR_SUCCESS &&
               stringToPath(instance, "/interaction_profiles/oculus/touch_controller",
                        &touchProfile) == XR_SUCCESS,
           "intern XR_EXT_hand_interaction and Touch profiles");
    expect(stringToPath(instance, "/user/hand/left/input/pinch_ext/pose",
                        &touchPinchPosePath) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/left/input/poke_ext/pose",
                        &touchPokePosePath) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/left/input/aim/pose",
                        &aimPosePath) == XR_SUCCESS,
           "intern Touch-profile pinch, poke and aim pose paths");
    expect(stringToPath(instance, "/user/hand/left/input/pinch_ext/value",
                        &pinchValuePath) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/left/input/pinch_ext/ready_ext",
                        &pinchReadyPath) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/left/input/grasp_ext/value",
                        &graspValuePath) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/left/input/grasp_ext/ready_ext",
                        &graspReadyPath) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/left/input/aim_activate_ext/value",
                        &aimActivatePath) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/left/input/aim_activate_ext/ready_ext",
                        &aimActivateReadyPath) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/left/input/pinch_ext/pose",
                        &pinchPosePath) == XR_SUCCESS &&
               stringToPath(instance, "/user/hand/left/input/poke_ext/pose",
                        &pokePosePath) == XR_SUCCESS,
           "intern hand-interaction value, readiness and pose binding paths");

    XrActionSetCreateInfo setInfo{};
    setInfo.type = XR_TYPE_ACTION_SET_CREATE_INFO;
    std::strncpy(setInfo.actionSetName, "hand_interaction",
                 sizeof(setInfo.actionSetName) - 1);
    std::strncpy(setInfo.localizedActionSetName, "Hand Interaction",
                 sizeof(setInfo.localizedActionSetName) - 1);
    XrActionSet actionSet = XR_NULL_HANDLE;
    expect(createActionSet(instance, &setInfo, &actionSet) == XR_SUCCESS,
           "create hand-interaction action set");

    const XrPath leftSubaction[] = {leftHand};
    auto makeAction = [&](const char* name, XrActionType type) {
        XrActionCreateInfo info{};
        info.type = XR_TYPE_ACTION_CREATE_INFO;
        std::strncpy(info.actionName, name, sizeof(info.actionName) - 1);
        std::strncpy(info.localizedActionName, name, sizeof(info.localizedActionName) - 1);
        info.actionType = type;
        info.countSubactionPaths = 1;
        info.subactionPaths = leftSubaction;
        XrAction action = XR_NULL_HANDLE;
        expect(createAction(actionSet, &info, &action) == XR_SUCCESS,
               "create hand-interaction action");
        return action;
    };
    const XrAction pinchValueAction = makeAction("pinch_value", XR_ACTION_TYPE_FLOAT_INPUT);
    const XrAction pinchReadyAction = makeAction("pinch_ready", XR_ACTION_TYPE_BOOLEAN_INPUT);
    const XrAction graspValueAction = makeAction("grasp_value", XR_ACTION_TYPE_FLOAT_INPUT);
    const XrAction graspReadyAction = makeAction("grasp_ready", XR_ACTION_TYPE_BOOLEAN_INPUT);
    const XrAction aimActivateValueAction = makeAction(
        "aim_activate_value", XR_ACTION_TYPE_FLOAT_INPUT);
    const XrAction aimActivateReadyAction = makeAction(
        "aim_activate_ready", XR_ACTION_TYPE_BOOLEAN_INPUT);
    const XrAction pinchPoseAction = makeAction("pinch_pose", XR_ACTION_TYPE_POSE_INPUT);
    const XrAction pokePoseAction = makeAction("poke_pose", XR_ACTION_TYPE_POSE_INPUT);
    const XrAction aimPoseAction = makeAction("aim_pose", XR_ACTION_TYPE_POSE_INPUT);

    const XrActionSuggestedBinding bindings[] = {
        {pinchValueAction, pinchValuePath},
        {pinchReadyAction, pinchReadyPath},
        {graspValueAction, graspValuePath},
        {graspReadyAction, graspReadyPath},
        {aimActivateValueAction, aimActivatePath},
        {aimActivateReadyAction, aimActivateReadyPath},
        {pinchPoseAction, pinchPosePath},
        {pokePoseAction, pokePosePath},
        {aimPoseAction, aimPosePath}
    };
    XrInteractionProfileSuggestedBinding suggested{};
    suggested.type = XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING;
    suggested.interactionProfile = handProfile;
    suggested.countSuggestedBindings = static_cast<std::uint32_t>(
        sizeof(bindings) / sizeof(bindings[0]));
    suggested.suggestedBindings = bindings;
    expect(suggestBindings(instance, &suggested) == XR_SUCCESS,
           "accept standard hand-interaction bindings");
    const XrActionSuggestedBinding touchPoseBindings[] = {
        {pinchPoseAction, touchPinchPosePath},
        {pokePoseAction, touchPokePosePath},
        {aimPoseAction, aimPosePath}
    };
    suggested.interactionProfile = touchProfile;
    suggested.countSuggestedBindings = static_cast<std::uint32_t>(
        sizeof(touchPoseBindings) / sizeof(touchPoseBindings[0]));
    suggested.suggestedBindings = touchPoseBindings;
    expect(suggestBindings(instance, &suggested) == XR_SUCCESS,
           "accept required pinch and poke pose bindings on a Touch controller profile");

    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    expect(getSystem(instance, &systemInfo, &system) == XR_SUCCESS,
           "get system for hand-interaction session");
    XrGraphicsRequirementsOpenGLESKHR requirements{};
    requirements.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
    expect(getRequirements(instance, system, &requirements) == XR_SUCCESS,
           "query GLES requirements for hand-interaction session");

    XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding{};
    graphicsBinding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
    graphicsBinding.display = reinterpret_cast<void*>(0x1000);
    graphicsBinding.config = reinterpret_cast<void*>(0x2000);
    graphicsBinding.context = reinterpret_cast<void*>(0x3000);
    XrSessionCreateInfo sessionInfo{};
    sessionInfo.type = XR_TYPE_SESSION_CREATE_INFO;
    sessionInfo.next = &graphicsBinding;
    sessionInfo.systemId = system;
    XrSession session = XR_NULL_HANDLE;
    expect(createSession(instance, &sessionInfo, &session) == XR_SUCCESS,
           "create session for hand-interaction actions");

    XrSessionActionSetsAttachInfo attachInfo{};
    attachInfo.type = XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO;
    attachInfo.countActionSets = 1;
    attachInfo.actionSets = &actionSet;
    expect(attachActionSets(session, &attachInfo) == XR_SUCCESS,
           "attach hand-interaction action set");
    XrSessionBeginInfo beginInfo{};
    beginInfo.type = XR_TYPE_SESSION_BEGIN_INFO;
    beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    expect(beginSession(session, &beginInfo) == XR_SUCCESS,
           "begin hand-interaction session");

    // Joint samples are palm-local. Thumb/index tip positions deliberately differ
    // so pinch and poke action spaces can be checked independently.
    std::array<float, XR_HAND_JOINT_COUNT_EXT * 8u> joints{};
    for (std::uint32_t joint = 0; joint < XR_HAND_JOINT_COUNT_EXT; ++joint) {
        const std::size_t offset = static_cast<std::size_t>(joint) * 8u;
        joints[offset + 3u] = 0.009f;
        joints[offset + 7u] = 1.0f;
    }
    joints[5u * 8u] = 0.02f;
    joints[5u * 8u + 1u] = 0.01f;
    joints[10u * 8u] = 0.04f;
    joints[10u * 8u + 1u] = 0.03f;
    joints[10u * 8u + 2u] = 0.01f;
    // Point with the index finger while curling the middle, ring and little
    // fingers. This is an aim_activate gesture, distinct from pinch strength.
    // Extend the index chain from a compact metacarpal base without moving
    // the original fingertip. Pinch/poke assertions below depend on that tip.
    joints[6u * 8u] = 0.014f;
    joints[6u * 8u + 1u] = 0.008f;
    joints[7u * 8u] = 0.016f;
    joints[7u * 8u + 1u] = 0.011f;
    joints[8u * 8u] = 0.024f;
    joints[8u * 8u + 1u] = 0.017f;
    joints[9u * 8u] = 0.032f;
    joints[9u * 8u + 1u] = 0.023f;
    joints[11u * 8u] = 0.015f;
    joints[11u * 8u + 1u] = -0.015f;
    joints[15u * 8u] = 0.02f;
    joints[15u * 8u + 1u] = -0.015f;
    joints[16u * 8u] = -0.01f;
    joints[16u * 8u + 1u] = -0.02f;
    joints[20u * 8u] = -0.005f;
    joints[20u * 8u + 1u] = -0.02f;
    joints[21u * 8u] = -0.02f;
    joints[21u * 8u + 1u] = -0.015f;
    joints[25u * 8u] = -0.01f;
    joints[25u * 8u + 1u] = -0.02f;
    endxrSetControllerInput(0, 1, 0.82f, 0.64f, 0.0f, 0.0f, 0);
    endxrSetHandPose(0, 1, 0.25f, 0.10f, -1.5f, 0.0f, 0.0f, 0.0f, 1.0f);
    endxrSetHandJointData(0, 1, joints.data(), static_cast<std::uint32_t>(joints.size()));
    endxrSetHeadOrientation(0.0f, 0.0f, 0.0f, 1.0f);

    XrActiveActionSet activeSet{actionSet, XR_NULL_PATH};
    XrActionsSyncInfo syncInfo{};
    syncInfo.type = XR_TYPE_ACTIONS_SYNC_INFO;
    syncInfo.countActiveActionSets = 1;
    syncInfo.activeActionSets = &activeSet;
    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "sync camera-driven hand interaction sample");

    XrInteractionProfileState profileState{};
    profileState.type = XR_TYPE_INTERACTION_PROFILE_STATE;
    expect(currentProfile(session, leftHand, &profileState) == XR_SUCCESS &&
               profileState.interactionProfile == handProfile,
           "select hand-interaction profile when fresh hand joints are active");

    XrActionStateGetInfo poseGetInfo{};
    poseGetInfo.type = XR_TYPE_ACTION_STATE_GET_INFO;
    poseGetInfo.action = pinchPoseAction;
    poseGetInfo.subactionPath = leftHand;
    XrActionStatePose poseState{};
    poseState.type = XR_TYPE_ACTION_STATE_POSE;
    expect(getPose(session, &poseGetInfo, &poseState) == XR_SUCCESS &&
               poseState.isActive == XR_TRUE,
           "expose the pinch action pose while the hand-interaction profile is active");

    XrActionStateGetInfo getInfo{};
    getInfo.type = XR_TYPE_ACTION_STATE_GET_INFO;
    getInfo.subactionPath = leftHand;
    XrActionStateFloat floatState{};
    floatState.type = XR_TYPE_ACTION_STATE_FLOAT;
    getInfo.action = pinchValueAction;
    expect(getFloat(session, &getInfo, &floatState) == XR_SUCCESS &&
               floatState.isActive == XR_TRUE &&
               std::abs(floatState.currentState - 0.82f) < 0.0001f,
           "map MediaPipe pinch strength to pinch_ext/value");
    getInfo.action = graspValueAction;
    expect(getFloat(session, &getInfo, &floatState) == XR_SUCCESS &&
               floatState.isActive == XR_TRUE &&
               std::abs(floatState.currentState - 0.64f) < 0.0001f,
           "map MediaPipe grip strength to grasp_ext/value");
    getInfo.action = aimActivateValueAction;
    expect(getFloat(session, &getInfo, &floatState) == XR_SUCCESS &&
               floatState.isActive == XR_TRUE &&
               floatState.currentState > 0.5f,
           "derive aim_activate independently from index pointing and curled support fingers");

    XrActionStateBoolean booleanState{};
    booleanState.type = XR_TYPE_ACTION_STATE_BOOLEAN;
    getInfo.action = pinchReadyAction;
    expect(getBoolean(session, &getInfo, &booleanState) == XR_SUCCESS &&
               booleanState.isActive == XR_TRUE &&
               booleanState.currentState == XR_TRUE,
           "report pinch readiness while all camera hand joints are fresh");
    getInfo.action = graspReadyAction;
    expect(getBoolean(session, &getInfo, &booleanState) == XR_SUCCESS &&
               booleanState.isActive == XR_TRUE &&
               booleanState.currentState == XR_TRUE,
           "report grasp readiness while all camera hand joints are fresh");
    getInfo.action = aimActivateReadyAction;
    expect(getBoolean(session, &getInfo, &booleanState) == XR_SUCCESS &&
               booleanState.isActive == XR_TRUE &&
               booleanState.currentState == XR_TRUE,
           "report aim activation readiness while the pointing hand joints are fresh");

    auto makeActionSpace = [&](XrAction action) {
        XrActionSpaceCreateInfo info{};
        info.type = XR_TYPE_ACTION_SPACE_CREATE_INFO;
        info.action = action;
        info.subactionPath = leftHand;
        info.poseInActionSpace.orientation.w = 1.0f;
        XrSpace space = XR_NULL_HANDLE;
        expect(createActionSpace(session, &info, &space) == XR_SUCCESS,
               "create camera hand-interaction action space");
        return space;
    };
    const XrSpace pinchSpace = makeActionSpace(pinchPoseAction);
    const XrSpace pokeSpace = makeActionSpace(pokePoseAction);
    const XrSpace aimSpace = makeActionSpace(aimPoseAction);

    XrReferenceSpaceCreateInfo referenceInfo{};
    referenceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    referenceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    referenceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    XrSpace localSpace = XR_NULL_HANDLE;
    expect(createReferenceSpace(session, &referenceInfo, &localSpace) == XR_SUCCESS,
           "create LOCAL base space for hand interaction");
    XrSpaceLocation location{};
    location.type = XR_TYPE_SPACE_LOCATION;
    expect(locateSpace(pinchSpace, localSpace, 1, &location) == XR_SUCCESS &&
               (location.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
               std::abs(location.pose.position.x - 0.28f) < 0.0001f &&
               std::abs(location.pose.position.y - 0.12f) < 0.0001f,
           "locate pinch pose at midpoint of camera thumb and index tips");
    location = {};
    location.type = XR_TYPE_SPACE_LOCATION;
    expect(locateSpace(pokeSpace, localSpace, 1, &location) == XR_SUCCESS &&
               (location.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
               std::abs(location.pose.position.x - 0.29f) < 0.0001f &&
               std::abs(location.pose.position.y - 0.13f) < 0.0001f &&
               std::abs(location.pose.position.z + 1.49f) < 0.0001f,
           "locate poke pose at the camera-derived index fingertip");
    location = {};
    location.type = XR_TYPE_SPACE_LOCATION;
    expect(locateSpace(aimSpace, localSpace, 1, &location) == XR_SUCCESS &&
               (location.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
               std::abs(location.pose.position.x - 0.25f) < 0.0001f &&
               std::abs(location.pose.position.y - 0.10f) < 0.0001f &&
               std::abs(location.pose.position.z + 1.62f) < 0.0001f,
           "locate aim pose ahead of the palm for a stable pointing ray");

    // When the joint stream is temporarily unavailable, the hand-interaction
    // profile must remain usable from the active controller-style input sample.
    endxrSetHandJointData(0, 0, nullptr, 0);
    expect(syncActions(session, &syncInfo) == XR_SUCCESS,
           "sync controller-style sample without camera joints");
    expect(currentProfile(session, leftHand, &profileState) == XR_SUCCESS &&
               profileState.interactionProfile == handProfile,
           "keep hand interaction bindings active for a controller-style sample without joints");
    getInfo.action = aimActivateValueAction;
    expect(getFloat(session, &getInfo, &floatState) == XR_SUCCESS &&
               floatState.isActive == XR_TRUE &&
               std::abs(floatState.currentState - 0.82f) < 0.0001f,
           "map controller trigger to aim_activate when no hand-joint stream is available");
    getInfo.action = aimActivateReadyAction;
    expect(getBoolean(session, &getInfo, &booleanState) == XR_SUCCESS &&
               booleanState.isActive == XR_TRUE &&
               booleanState.currentState == XR_TRUE,
           "controller input keeps aim activation ready without camera joints");
    poseState = {};
    poseState.type = XR_TYPE_ACTION_STATE_POSE;
    expect(getPose(session, &poseGetInfo, &poseState) == XR_SUCCESS &&
               poseState.isActive == XR_TRUE,
           "keep the pinch action pose active on the Touch controller profile");
    location = {};
    location.type = XR_TYPE_SPACE_LOCATION;
    expect(locateSpace(pinchSpace, localSpace, 1, &location) == XR_SUCCESS &&
               (location.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
               std::abs(location.pose.position.x - 0.25f) < 0.0001f &&
               std::abs(location.pose.position.y - 0.10f) < 0.0001f &&
               std::abs(location.pose.position.z + 1.56f) < 0.0001f,
           "provide a grip-relative pinch pose for controller profiles");
    location = {};
    location.type = XR_TYPE_SPACE_LOCATION;
    expect(locateSpace(pokeSpace, localSpace, 1, &location) == XR_SUCCESS &&
               (location.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
               std::abs(location.pose.position.x - 0.25f) < 0.0001f &&
               std::abs(location.pose.position.y - 0.10f) < 0.0001f &&
               std::abs(location.pose.position.z + 1.60f) < 0.0001f,
           "provide a grip-relative poke pose for controller profiles");
    location = {};
    location.type = XR_TYPE_SPACE_LOCATION;
    expect(locateSpace(aimSpace, localSpace, 1, &location) == XR_SUCCESS &&
               (location.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
               std::abs(location.pose.position.x - 0.25f) < 0.0001f &&
               std::abs(location.pose.position.y - 0.10f) < 0.0001f &&
               std::abs(location.pose.position.z + 1.62f) < 0.0001f,
           "keep the controller-backed aim pose ahead of the grip pose");

    endxrClearControllerInput();
    endxrClearHandPoses();
    endxrClearHeadOrientation();
    expect(requestExitSession(session) == XR_SUCCESS,
           "request hand-interaction session exit");
    expect(endSession(session) == XR_SUCCESS,
           "end hand-interaction session before teardown");
    expect(destroySpace(pinchSpace) == XR_SUCCESS &&
               destroySpace(pokeSpace) == XR_SUCCESS &&
               destroySpace(aimSpace) == XR_SUCCESS &&
               destroySpace(localSpace) == XR_SUCCESS,
           "destroy hand-interaction spaces");
    expect(destroySession(session) == XR_SUCCESS,
           "destroy hand-interaction session");
    for (XrAction action : {pinchValueAction, pinchReadyAction, graspValueAction,
                            graspReadyAction, aimActivateValueAction, aimActivateReadyAction,
                            pinchPoseAction, pokePoseAction, aimPoseAction}) {
        expect(destroyAction(action) == XR_SUCCESS,
               "destroy hand-interaction action");
    }
    expect(destroyActionSet(actionSet) == XR_SUCCESS,
           "destroy hand-interaction action set");
    expect(destroyInstance(instance) == XR_SUCCESS,
           "destroy hand-interaction instance");
}

void testGetInstanceProcAddrConformance() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before GIPA conformance checks");

    PFN_xrVoidFunction function = nullptr;
    expect(request.getInstanceProcAddr(
               XR_NULL_HANDLE, "xrEnumerateInstanceExtensionProperties", &function) ==
               XR_SUCCESS && function != nullptr,
           "resolve extension enumeration without an instance");
    expect(request.getInstanceProcAddr(
               XR_NULL_HANDLE, "xrEnumerateApiLayerProperties", &function) ==
               XR_SUCCESS && function != nullptr,
           "resolve API-layer enumeration without an instance");
    expect(request.getInstanceProcAddr(
               XR_NULL_HANDLE, "xrCreateInstance", &function) ==
               XR_SUCCESS && function != nullptr,
           "resolve instance creation without an instance");

    const char* instanceOnlyNames[] = {
        "xrGetInstanceProcAddr",
        "xrDestroyInstance",
        "xrCreateActionSet",
        "xrSyncActions",
        "xrGetActionStatePose",
        "xrGetOpenGLESGraphicsRequirementsKHR",
        "xrReleaseSwapchainImage"
    };
    for (const char* name : instanceOnlyNames) {
        function = reinterpret_cast<PFN_xrVoidFunction>(static_cast<std::uintptr_t>(1));
        expect(request.getInstanceProcAddr(XR_NULL_HANDLE, name, &function) ==
                   XR_ERROR_HANDLE_INVALID && function == nullptr,
               "reject non-global command lookup with XR_NULL_HANDLE");
    }

    XrInstance noGlesInstance = createInstance(request.getInstanceProcAddr, false, false);
    function = nullptr;
    expect(request.getInstanceProcAddr(
               noGlesInstance, "xrCreateActionSet", &function) ==
               XR_SUCCESS && function != nullptr,
           "resolve core action functions with a valid instance");
    expect(request.getInstanceProcAddr(
               noGlesInstance, "xrSyncActions", &function) ==
               XR_SUCCESS && function != nullptr,
           "resolve action synchronization with a valid instance");
    expect(request.getInstanceProcAddr(
               noGlesInstance, "xrGetActionStatePose", &function) ==
               XR_SUCCESS && function != nullptr,
           "resolve pose action-state query with a valid instance");
    function = reinterpret_cast<PFN_xrVoidFunction>(static_cast<std::uintptr_t>(1));
    expect(request.getInstanceProcAddr(
               noGlesInstance, "xrGetOpenGLESGraphicsRequirementsKHR", &function) ==
               XR_ERROR_FUNCTION_UNSUPPORTED && function == nullptr,
           "hide GLES extension entry point when the extension is not enabled");
    expect(request.getInstanceProcAddr(
               noGlesInstance, "xrCreateHandTrackerEXT", &function) ==
               XR_ERROR_FUNCTION_UNSUPPORTED && function == nullptr,
           "hide hand-tracking extension entry points when the extension is not enabled");
    auto destroyNoGles = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, noGlesInstance, "xrDestroyInstance"));
    expect(destroyNoGles(noGlesInstance) == XR_SUCCESS,
           "destroy instance used for disabled-extension GIPA check");

    XrInstance handTrackingInstance =
        createInstance(request.getInstanceProcAddr, false, true, true);
    function = nullptr;
    expect(request.getInstanceProcAddr(
               handTrackingInstance, "xrCreateHandTrackerEXT", &function) ==
               XR_SUCCESS && function != nullptr,
           "resolve hand-tracking extension entry point after enabling it");
    auto destroyHandTrackingInstance = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, handTrackingInstance, "xrDestroyInstance"));
    expect(destroyHandTrackingInstance(handTrackingInstance) == XR_SUCCESS,
           "destroy hand-tracking-enabled GIPA test instance");

    XrInstance glesInstance = createInstance(request.getInstanceProcAddr, false, true);
    function = nullptr;
    expect(request.getInstanceProcAddr(
               glesInstance, "xrGetOpenGLESGraphicsRequirementsKHR", &function) ==
               XR_SUCCESS && function != nullptr,
           "resolve GLES extension entry point after enabling the extension");
    auto destroyGles = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, glesInstance, "xrDestroyInstance"));
    expect(destroyGles(glesInstance) == XR_SUCCESS,
           "destroy instance used for enabled-extension GIPA check");
}

void testReferenceSpacesFloorHeightAndLocateSpaces() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before reference space and locateSpaces tests");

    // 1. OpenXR 1.0 without XR_EXT_local_floor / XR_KHR_locate_spaces
    XrInstance v10Instance = createInstance(request.getInstanceProcAddr, false, true);
    PFN_xrVoidFunction fn = nullptr;
    expect(request.getInstanceProcAddr(v10Instance, "xrLocateSpaces", &fn) ==
               XR_ERROR_FUNCTION_UNSUPPORTED && fn == nullptr,
           "hide core 1.1 xrLocateSpaces on OpenXR 1.0 instances");
    expect(request.getInstanceProcAddr(v10Instance, "xrLocateSpacesKHR", &fn) ==
               XR_ERROR_FUNCTION_UNSUPPORTED && fn == nullptr,
           "hide xrLocateSpacesKHR when extension is not enabled");

    auto getSystem10 = reinterpret_cast<PFN_xrGetSystem>(
        resolve(request.getInstanceProcAddr, v10Instance, "xrGetSystem"));
    XrSystemGetInfo sysInfo{XR_TYPE_SYSTEM_GET_INFO, nullptr, XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY};
    XrSystemId sys10 = XR_NULL_SYSTEM_ID;
    expect(getSystem10(v10Instance, &sysInfo, &sys10) == XR_SUCCESS, "get system for 1.0 space test");
    auto getReq10 = reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(
        resolve(request.getInstanceProcAddr, v10Instance, "xrGetOpenGLESGraphicsRequirementsKHR"));
    XrGraphicsRequirementsOpenGLESKHR req10{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR, nullptr, 0, 0};
    expect(getReq10(v10Instance, sys10, &req10) == XR_SUCCESS, "query GLES reqs for 1.0 space test");
    XrGraphicsBindingOpenGLESAndroidKHR binding10{
        XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR, nullptr,
        reinterpret_cast<void*>(0x1000), reinterpret_cast<void*>(0x2000), reinterpret_cast<void*>(0x3000)
    };
    XrSessionCreateInfo sessInfo10{XR_TYPE_SESSION_CREATE_INFO, &binding10, 0, sys10};
    auto createSess10 = reinterpret_cast<PFN_xrCreateSession>(
        resolve(request.getInstanceProcAddr, v10Instance, "xrCreateSession"));
    XrSession sess10 = XR_NULL_HANDLE;
    expect(createSess10(v10Instance, &sessInfo10, &sess10) == XR_SUCCESS, "create 1.0 session");

    auto enumRef10 = reinterpret_cast<PFN_xrEnumerateReferenceSpaces>(
        resolve(request.getInstanceProcAddr, v10Instance, "xrEnumerateReferenceSpaces"));
    std::uint32_t refCount10 = 0;
    XrReferenceSpaceType refTypes10[4]{};
    expect(enumRef10(sess10, 4, &refCount10, refTypes10) == XR_SUCCESS && refCount10 == 3 &&
               refTypes10[0] == XR_REFERENCE_SPACE_TYPE_VIEW &&
               refTypes10[1] == XR_REFERENCE_SPACE_TYPE_LOCAL &&
               refTypes10[2] == XR_REFERENCE_SPACE_TYPE_STAGE,
           "enumerate VIEW, LOCAL, and STAGE without LOCAL_FLOOR on base OpenXR 1.0");
    auto createSpace10 = reinterpret_cast<PFN_xrCreateReferenceSpace>(
        resolve(request.getInstanceProcAddr, v10Instance, "xrCreateReferenceSpace"));
    XrReferenceSpaceCreateInfo floorCreate{
        XR_TYPE_REFERENCE_SPACE_CREATE_INFO, nullptr, XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR,
        {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}}
    };
    XrSpace rejectedFloor = XR_NULL_HANDLE;
    expect(createSpace10(sess10, &floorCreate, &rejectedFloor) == XR_ERROR_REFERENCE_SPACE_UNSUPPORTED,
           "reject LOCAL_FLOOR on 1.0 without XR_EXT_local_floor enabled");
    auto destroySess10 = reinterpret_cast<PFN_xrDestroySession>(
        resolve(request.getInstanceProcAddr, v10Instance, "xrDestroySession"));
    auto destroyInst10 = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, v10Instance, "xrDestroyInstance"));
    expect(destroySess10(sess10) == XR_SUCCESS && destroyInst10(v10Instance) == XR_SUCCESS,
           "clean up 1.0 space test instance");

    // 2. OpenXR 1.1 with XR_EXT_local_floor & XR_KHR_locate_spaces
    const char* extraExts[] = {
        XR_EXT_LOCAL_FLOOR_EXTENSION_NAME,
        XR_KHR_LOCATE_SPACES_EXTENSION_NAME
    };
    XrInstance v11Instance = createInstance(
        request.getInstanceProcAddr, false, true, false, false,
        XR_MAKE_VERSION(1, 1, 0), extraExts, 2);

    auto locateSpaces = reinterpret_cast<PFN_xrLocateSpaces>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrLocateSpaces"));
    auto locateSpacesKhr = reinterpret_cast<PFN_xrLocateSpacesKHR>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrLocateSpacesKHR"));

    auto getSystem = reinterpret_cast<PFN_xrGetSystem>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrGetSystem"));
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    expect(getSystem(v11Instance, &sysInfo, &systemId) == XR_SUCCESS, "get system for 1.1 test");
    auto getReqs = reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrGetOpenGLESGraphicsRequirementsKHR"));
    expect(getReqs(v11Instance, systemId, &req10) == XR_SUCCESS, "query GLES reqs for 1.1 test");
    XrSessionCreateInfo sessInfo11{XR_TYPE_SESSION_CREATE_INFO, &binding10, 0, systemId};
    auto createSess = reinterpret_cast<PFN_xrCreateSession>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrCreateSession"));
    XrSession session = XR_NULL_HANDLE;
    expect(createSess(v11Instance, &sessInfo11, &session) == XR_SUCCESS, "create 1.1 session");

    auto beginSess = reinterpret_cast<PFN_xrBeginSession>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrBeginSession"));
    XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO, nullptr, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO};
    expect(beginSess(session, &beginInfo) == XR_SUCCESS, "begin 1.1 session");

    auto enumRef = reinterpret_cast<PFN_xrEnumerateReferenceSpaces>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrEnumerateReferenceSpaces"));
    std::uint32_t refCount = 0;
    XrReferenceSpaceType refTypes[4]{};
    expect(enumRef(session, 4, &refCount, refTypes) == XR_SUCCESS && refCount == 4 &&
               refTypes[3] == XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR,
           "enumerate LOCAL_FLOOR alongside VIEW, LOCAL, and STAGE in OpenXR 1.1");

    auto createSpace = reinterpret_cast<PFN_xrCreateReferenceSpace>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrCreateReferenceSpace"));
    auto destroySpace = reinterpret_cast<PFN_xrDestroySpace>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrDestroySpace"));
    XrReferenceSpaceCreateInfo spaceCreate{
        XR_TYPE_REFERENCE_SPACE_CREATE_INFO, nullptr, XR_REFERENCE_SPACE_TYPE_LOCAL,
        {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}}
    };
    XrSpace localSpace = XR_NULL_HANDLE;
    XrSpace viewSpace = XR_NULL_HANDLE;
    XrSpace stageSpace = XR_NULL_HANDLE;
    XrSpace floorSpace = XR_NULL_HANDLE;
    expect(createSpace(session, &spaceCreate, &localSpace) == XR_SUCCESS, "create LOCAL");
    spaceCreate.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    expect(createSpace(session, &spaceCreate, &viewSpace) == XR_SUCCESS, "create VIEW");
    spaceCreate.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE;
    expect(createSpace(session, &spaceCreate, &stageSpace) == XR_SUCCESS, "create STAGE");
    spaceCreate.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR;
    expect(createSpace(session, &spaceCreate, &floorSpace) == XR_SUCCESS, "create LOCAL_FLOOR");

    endxrSetHeadOrientation(0.0f, 0.0f, 0.0f, 1.0f);
    auto locateViews = reinterpret_cast<PFN_xrLocateViews>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrLocateViews"));
    XrViewLocateInfo viewLocate{
        XR_TYPE_VIEW_LOCATE_INFO, nullptr, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 1, floorSpace
    };
    XrViewState viewState{XR_TYPE_VIEW_STATE, nullptr, 0};
    XrView views[2]{{XR_TYPE_VIEW, nullptr, {}, {}}, {XR_TYPE_VIEW, nullptr, {}, {}}};
    std::uint32_t viewCount = 0;
    expect(locateViews(session, &viewLocate, &viewState, 2, &viewCount, views) == XR_SUCCESS &&
               (viewState.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT) != 0 &&
               std::abs(views[0].pose.position.y - 1.65f) < 0.001f &&
               std::abs(views[0].pose.position.x + 0.032f) < 0.001f,
           "elevate stereo eyes to default 1.65m eye height when located in LOCAL_FLOOR");

    const XrSpace targetSpaces[3] = {viewSpace, stageSpace, floorSpace};
    XrSpaceLocationData locationData[3]{};
    XrSpaceLocations batchOutput{XR_TYPE_SPACE_LOCATIONS, nullptr, 3, locationData};
    XrSpacesLocateInfo batchInfo{XR_TYPE_SPACES_LOCATE_INFO, nullptr, localSpace, 1, 3, targetSpaces};
    expect(locateSpaces(session, &batchInfo, &batchOutput) == XR_SUCCESS &&
               batchOutput.locationCount == 3 &&
               (locationData[0].locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0 &&
               std::abs(locationData[1].pose.position.y + 1.65f) < 0.001f &&
               std::abs(locationData[2].pose.position.y + 1.65f) < 0.001f,
           "batch locate VIEW, STAGE, and LOCAL_FLOOR via OpenXR 1.1 xrLocateSpaces");

    XrSpaceLocationData khrData[2]{};
    XrSpaceLocationsKHR khrOutput{XR_TYPE_SPACE_LOCATIONS_KHR, nullptr, 2, khrData};
    XrSpacesLocateInfoKHR khrInfo{XR_TYPE_SPACES_LOCATE_INFO_KHR, nullptr, floorSpace, 1, 2, targetSpaces};
    expect(locateSpacesKhr(session, &khrInfo, &khrOutput) == XR_SUCCESS &&
               khrOutput.locationCount == 2 &&
               std::abs(khrData[0].pose.position.y - 1.65f) < 0.001f &&
               std::abs(khrData[1].pose.position.y) < 0.001f,
           "batch locate spaces relative to floor via XR_KHR_locate_spaces");

    endxrClearHeadOrientation();
    auto requestExit = reinterpret_cast<PFN_xrRequestExitSession>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrRequestExitSession"));
    auto endSess = reinterpret_cast<PFN_xrEndSession>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrEndSession"));
    expect(requestExit(session) == XR_SUCCESS && endSess(session) == XR_SUCCESS,
           "end 1.1 reference-space test session");
    expect(destroySpace(localSpace) == XR_SUCCESS && destroySpace(viewSpace) == XR_SUCCESS &&
               destroySpace(stageSpace) == XR_SUCCESS && destroySpace(floorSpace) == XR_SUCCESS,
           "destroy 1.1 test spaces");
    auto destroySess = reinterpret_cast<PFN_xrDestroySession>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrDestroySession"));
    auto destroyInst = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, v11Instance, "xrDestroyInstance"));
    expect(destroySess(session) == XR_SUCCESS && destroyInst(v11Instance) == XR_SUCCESS,
           "destroy 1.1 reference-space test instance");
}

void testSwapchainFormatsMultiviewDepthAndSingleProjectionLayer() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before swapchain format and multiview test");
    XrInstance instance = createInstance(request.getInstanceProcAddr, false, true);

    auto getSystem = reinterpret_cast<PFN_xrGetSystem>(
        resolve(request.getInstanceProcAddr, instance, "xrGetSystem"));
    XrSystemGetInfo sysInfo{XR_TYPE_SYSTEM_GET_INFO, nullptr, XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY};
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    expect(getSystem(instance, &sysInfo, &systemId) == XR_SUCCESS, "get HMD system");
    auto getReqs = reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(
        resolve(request.getInstanceProcAddr, instance, "xrGetOpenGLESGraphicsRequirementsKHR"));
    XrGraphicsRequirementsOpenGLESKHR reqs{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR, nullptr, 0, 0};
    expect(getReqs(instance, systemId, &reqs) == XR_SUCCESS, "query GLES requirements");

    XrGraphicsBindingOpenGLESAndroidKHR binding{
        XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR, nullptr,
        reinterpret_cast<void*>(0x1000), reinterpret_cast<void*>(0x2000), reinterpret_cast<void*>(0x3000)
    };
    XrSessionCreateInfo sessInfo{XR_TYPE_SESSION_CREATE_INFO, &binding, 0, systemId};
    auto createSess = reinterpret_cast<PFN_xrCreateSession>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateSession"));
    XrSession session = XR_NULL_HANDLE;
    expect(createSess(instance, &sessInfo, &session) == XR_SUCCESS, "create session");

    auto enumFormats = reinterpret_cast<PFN_xrEnumerateSwapchainFormats>(
        resolve(request.getInstanceProcAddr, instance, "xrEnumerateSwapchainFormats"));
    std::uint32_t formatCount = 0;
    std::int64_t formats[8]{};
    expect(enumFormats(session, 8, &formatCount, formats) == XR_SUCCESS && formatCount == 5 &&
               formats[0] == 0x8C43 && // GL_SRGB8_ALPHA8
               formats[1] == 0x8058 && // GL_RGBA8
               formats[2] == 0x88F0 && // GL_DEPTH24_STENCIL8
               formats[3] == 0x81A6 && // GL_DEPTH_COMPONENT24
               formats[4] == 0x81A5,   // GL_DEPTH_COMPONENT16
           "enumerate sRGB, RGBA8, and depth GLES swapchain formats");

    auto createSwap = reinterpret_cast<PFN_xrCreateSwapchain>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateSwapchain"));
    auto destroySwap = reinterpret_cast<PFN_xrDestroySwapchain>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySwapchain"));
    auto acquire = reinterpret_cast<PFN_xrAcquireSwapchainImage>(
        resolve(request.getInstanceProcAddr, instance, "xrAcquireSwapchainImage"));
    auto wait = reinterpret_cast<PFN_xrWaitSwapchainImage>(
        resolve(request.getInstanceProcAddr, instance, "xrWaitSwapchainImage"));
    auto release = reinterpret_cast<PFN_xrReleaseSwapchainImage>(
        resolve(request.getInstanceProcAddr, instance, "xrReleaseSwapchainImage"));

    XrSwapchainCreateInfo mvInfo{
        XR_TYPE_SWAPCHAIN_CREATE_INFO, nullptr, 0,
        XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT |
            XR_SWAPCHAIN_USAGE_TRANSFER_SRC_BIT,
        0x8C43, 1, 960, 1080, 1, 2, 1
    };
    XrSwapchain mvSwap = XR_NULL_HANDLE;
    expect(createSwap(session, &mvInfo, &mvSwap) == XR_SUCCESS,
           "create sRGB GL_TEXTURE_2D_ARRAY multiview swapchain (arraySize=2)");

    XrSwapchainCreateInfo depthInfo{
        XR_TYPE_SWAPCHAIN_CREATE_INFO, nullptr, 0,
        XR_SWAPCHAIN_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT,
        0x88F0, 1, 960, 1080, 1, 2, 1
    };
    XrSwapchain depthSwap = XR_NULL_HANDLE;
    expect(createSwap(session, &depthInfo, &depthSwap) == XR_SUCCESS,
           "create GL_DEPTH24_STENCIL8 depth swapchain");

    XrSwapchainImageAcquireInfo acqInfo{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO, nullptr};
    XrSwapchainImageWaitInfo waitInfo{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO, nullptr, XR_INFINITE_DURATION};
    XrSwapchainImageReleaseInfo relInfo{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO, nullptr};
    std::uint32_t idx = 0;
    for (XrSwapchain swapchain : {mvSwap, depthSwap}) {
        expect(acquire(swapchain, &acqInfo, &idx) == XR_SUCCESS &&
                   wait(swapchain, &waitInfo) == XR_SUCCESS &&
                   release(swapchain, &relInfo) == XR_SUCCESS,
               "acquire/wait/release multiview or depth swapchain image");
    }

    auto beginSess = reinterpret_cast<PFN_xrBeginSession>(
        resolve(request.getInstanceProcAddr, instance, "xrBeginSession"));
    XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO, nullptr, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO};
    expect(beginSess(session, &beginInfo) == XR_SUCCESS, "begin session");

    auto waitFrame = reinterpret_cast<PFN_xrWaitFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrWaitFrame"));
    auto beginFrame = reinterpret_cast<PFN_xrBeginFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrBeginFrame"));
    auto endFrame = reinterpret_cast<PFN_xrEndFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrEndFrame"));

    XrFrameWaitInfo waitFrameInfo{XR_TYPE_FRAME_WAIT_INFO, nullptr};
    XrFrameState frameState{XR_TYPE_FRAME_STATE, nullptr, 0, 0, XR_FALSE};
    XrFrameBeginInfo beginFrameInfo{XR_TYPE_FRAME_BEGIN_INFO, nullptr};
    expect(waitFrame(session, &waitFrameInfo, &frameState) == XR_SUCCESS &&
               beginFrame(session, &beginFrameInfo) == XR_SUCCESS,
           "wait and begin multiview frame");

    XrCompositionLayerProjectionView mvViews[2]{};
    for (std::uint32_t eye = 0; eye < 2; ++eye) {
        mvViews[eye].type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
        mvViews[eye].subImage.swapchain = mvSwap;
        mvViews[eye].subImage.imageArrayIndex = eye;
        mvViews[eye].subImage.imageRect.extent = {960, 1080};
    }
    XrCompositionLayerProjection projection{
        XR_TYPE_COMPOSITION_LAYER_PROJECTION, nullptr, 0, XR_NULL_HANDLE, 2, mvViews
    };
    const XrCompositionLayerBaseHeader* projectionOnlyLayers[] = {
        reinterpret_cast<const XrCompositionLayerBaseHeader*>(&projection)
    };
    XrFrameEndInfo endInfo{
        XR_TYPE_FRAME_END_INFO, nullptr, frameState.predictedDisplayTime,
        XR_ENVIRONMENT_BLEND_MODE_OPAQUE, 1, projectionOnlyLayers
    };
    expect(endFrame(session, &endInfo) == XR_SUCCESS,
           "composite one stereo projection layer from a two-slice array swapchain");

    const XrCompositionLayerBaseHeader* excessiveLayers[] = {
        reinterpret_cast<const XrCompositionLayerBaseHeader*>(&projection),
        reinterpret_cast<const XrCompositionLayerBaseHeader*>(&projection)
    };
    expect(waitFrame(session, &waitFrameInfo, &frameState) == XR_SUCCESS &&
               beginFrame(session, &beginFrameInfo) == XR_SUCCESS,
           "begin unsupported multi-layer frame");
    endInfo.displayTime = frameState.predictedDisplayTime;
    endInfo.layerCount = 2;
    endInfo.layers = excessiveLayers;
    expect(endFrame(session, &endInfo) == XR_ERROR_LAYER_LIMIT_EXCEEDED,
           "reject multiple layers instead of silently dropping the extra layer");

    expect(waitFrame(session, &waitFrameInfo, &frameState) == XR_SUCCESS &&
               beginFrame(session, &beginFrameInfo) == XR_SUCCESS,
           "begin projection frame after rejected multi-layer submission");
    endInfo.displayTime = frameState.predictedDisplayTime;
    endInfo.layerCount = 1;
    endInfo.layers = projectionOnlyLayers;
    expect(endFrame(session, &endInfo) == XR_SUCCESS,
           "continue frame loop after a rejected multi-layer submission");

    expect(waitFrame(session, &waitFrameInfo, &frameState) == XR_SUCCESS &&
               beginFrame(session, &beginFrameInfo) == XR_SUCCESS,
           "begin invalid depth-as-color frame");
    mvViews[0].subImage.swapchain = depthSwap;
    endInfo.displayTime = frameState.predictedDisplayTime;
    expect(endFrame(session, &endInfo) == XR_ERROR_LAYER_INVALID,
           "reject a depth swapchain used as a projection color target");

    auto requestExit = reinterpret_cast<PFN_xrRequestExitSession>(
        resolve(request.getInstanceProcAddr, instance, "xrRequestExitSession"));
    auto endSess = reinterpret_cast<PFN_xrEndSession>(
        resolve(request.getInstanceProcAddr, instance, "xrEndSession"));
    expect(requestExit(session) == XR_SUCCESS && endSess(session) == XR_SUCCESS,
           "end multiview swapchain test session");
    expect(destroySwap(mvSwap) == XR_SUCCESS && destroySwap(depthSwap) == XR_SUCCESS,
           "destroy multiview and depth swapchains");
    auto destroySess = reinterpret_cast<PFN_xrDestroySession>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySession"));
    auto destroyInst = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance"));
    expect(destroySess(session) == XR_SUCCESS && destroyInst(instance) == XR_SUCCESS,
           "destroy multiview swapchain test session");
}

void testAndroidThreadAndTimespecExtensions() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before Android thread and timespec tests");

    XrInstance bare = createInstance(request.getInstanceProcAddr, false, true);
    PFN_xrVoidFunction fn = nullptr;
    for (const char* gatedName : {
             "xrSetAndroidApplicationThreadKHR",
             "xrConvertTimespecTimeToTimeKHR",
             "xrConvertTimeToTimespecTimeKHR",
             "xrPerfSettingsSetPerformanceLevelEXT",
             "xrEnumerateDisplayRefreshRatesFB",
             "xrGetDisplayRefreshRateFB",
             "xrRequestDisplayRefreshRateFB",
             "xrEnumerateColorSpacesFB",
             "xrSetColorSpaceFB"
         }) {
        fn = reinterpret_cast<PFN_xrVoidFunction>(static_cast<std::uintptr_t>(1));
        expect(request.getInstanceProcAddr(bare, gatedName, &fn) ==
                   XR_ERROR_FUNCTION_UNSUPPORTED && fn == nullptr,
               "hide extension entrypoint when extension is disabled or not advertised");
    }
    auto destroyBare = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, bare, "xrDestroyInstance"));
    expect(destroyBare(bare) == XR_SUCCESS, "destroy bare GIPA test instance");

    const char* extraExts[] = {
        ENDXR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME,
        ENDXR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME
    };
    XrInstance instance = createInstance(
        request.getInstanceProcAddr, false, true, false, false,
        XR_MAKE_VERSION(1, 0, 0), extraExts, 2);

    auto timespecToTime = reinterpret_cast<PFN_xrConvertTimespecTimeToTimeKHR>(
        resolve(request.getInstanceProcAddr, instance, "xrConvertTimespecTimeToTimeKHR"));
    auto timeToTimespec = reinterpret_cast<PFN_xrConvertTimeToTimespecTimeKHR>(
        resolve(request.getInstanceProcAddr, instance, "xrConvertTimeToTimespecTimeKHR"));
    struct timespec tsIn{};
    tsIn.tv_sec = 12;
    tsIn.tv_nsec = 345678901L;
    XrTime xrTime = 0;
    expect(timespecToTime(instance, &tsIn, &xrTime) == XR_SUCCESS &&
               xrTime == 12345678901LL,
           "convert timespec to XrTime nanoseconds");
    struct timespec tsOut{};
    expect(timeToTimespec(instance, xrTime, &tsOut) == XR_SUCCESS &&
               tsOut.tv_sec == 12 && tsOut.tv_nsec == 345678901L,
           "round-trip XrTime back to timespec");
    tsIn.tv_nsec = 1000000000L;
    expect(timespecToTime(instance, &tsIn, &xrTime) == XR_ERROR_VALIDATION_FAILURE,
           "reject out-of-range timespec nanoseconds");

    auto getSystem = reinterpret_cast<PFN_xrGetSystem>(
        resolve(request.getInstanceProcAddr, instance, "xrGetSystem"));
    XrSystemGetInfo sysInfo{XR_TYPE_SYSTEM_GET_INFO, nullptr, XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY};
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    expect(getSystem(instance, &sysInfo, &systemId) == XR_SUCCESS, "get HMD system");
    auto getReqs = reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(
        resolve(request.getInstanceProcAddr, instance, "xrGetOpenGLESGraphicsRequirementsKHR"));
    XrGraphicsRequirementsOpenGLESKHR reqs{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR, nullptr, 0, 0};
    expect(getReqs(instance, systemId, &reqs) == XR_SUCCESS, "query GLES requirements");
    XrGraphicsBindingOpenGLESAndroidKHR binding{
        XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR, nullptr,
        reinterpret_cast<void*>(0x1000), reinterpret_cast<void*>(0x2000), reinterpret_cast<void*>(0x3000)
    };
    XrSessionCreateInfo sessInfo{XR_TYPE_SESSION_CREATE_INFO, &binding, 0, systemId};
    auto createSess = reinterpret_cast<PFN_xrCreateSession>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateSession"));
    XrSession session = XR_NULL_HANDLE;
    expect(createSess(instance, &sessInfo, &session) == XR_SUCCESS, "create session");

    auto setThread = reinterpret_cast<PFN_xrSetAndroidApplicationThreadKHR>(
        resolve(request.getInstanceProcAddr, instance, "xrSetAndroidApplicationThreadKHR"));
    expect(setThread(session, XR_ANDROID_THREAD_TYPE_RENDERER_MAIN_KHR, 4242) == XR_SUCCESS,
           "accept best-effort renderer thread priority hint");
    expect(setThread(session, XR_ANDROID_THREAD_TYPE_APPLICATION_MAIN_KHR, 0) ==
               XR_ERROR_ANDROID_THREAD_SETTINGS_ID_INVALID_KHR,
           "reject zero thread id in xrSetAndroidApplicationThreadKHR");

    auto beginSess = reinterpret_cast<PFN_xrBeginSession>(
        resolve(request.getInstanceProcAddr, instance, "xrBeginSession"));
    XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO, nullptr, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO};
    expect(beginSess(session, &beginInfo) == XR_SUCCESS, "begin session");
    auto waitFrame = reinterpret_cast<PFN_xrWaitFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrWaitFrame"));
    auto beginFrame = reinterpret_cast<PFN_xrBeginFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrBeginFrame"));
    auto endFrame = reinterpret_cast<PFN_xrEndFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrEndFrame"));
    XrFrameWaitInfo waitInfo{XR_TYPE_FRAME_WAIT_INFO, nullptr};
    XrFrameState frameState{XR_TYPE_FRAME_STATE, nullptr, 0, 0, XR_FALSE};
    XrFrameBeginInfo beginFrameInfo{XR_TYPE_FRAME_BEGIN_INFO, nullptr};
    expect(waitFrame(session, &waitInfo, &frameState) == XR_SUCCESS &&
               frameState.predictedDisplayPeriod == 33333333 &&
               beginFrame(session, &beginFrameInfo) == XR_SUCCESS,
           "use conservative fixed 30 Hz prediction until display mode switching is implemented");
    XrFrameEndInfo endInfo{
        XR_TYPE_FRAME_END_INFO, nullptr, frameState.predictedDisplayTime,
        XR_ENVIRONMENT_BLEND_MODE_OPAQUE, 0, nullptr
    };
    expect(endFrame(session, &endInfo) == XR_SUCCESS, "complete empty frame");

    auto requestExit = reinterpret_cast<PFN_xrRequestExitSession>(
        resolve(request.getInstanceProcAddr, instance, "xrRequestExitSession"));
    auto endSess = reinterpret_cast<PFN_xrEndSession>(
        resolve(request.getInstanceProcAddr, instance, "xrEndSession"));
    expect(requestExit(session) == XR_SUCCESS && endSess(session) == XR_SUCCESS,
           "end thread/timespec extension test session");
    auto destroySess = reinterpret_cast<PFN_xrDestroySession>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySession"));
    auto destroyInst = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance"));
    expect(destroySess(session) == XR_SUCCESS && destroyInst(instance) == XR_SUCCESS,
           "clean up thread/timespec extension test session");
}
} // namespace

int main() {
    testNegotiation();
    testInstanceApiVersionCompatibility();
    testExtensionEnumerationAndInstance();
    testSystemDiscoveryAndGraphicsRequirements();
    testGlesSessionSwapchainAndFrameLifecycle();
    testActionInputApiLifecycleAndNeutralState();
    testHandInteractionProfileMapsCameraGesturesAndJointPoses();
    testGetInstanceProcAddrConformance();
    testReferenceSpacesFloorHeightAndLocateSpaces();
    testSwapchainFormatsMultiviewDepthAndSingleProjectionLayer();
    testAndroidThreadAndTimespecExtensions();
    std::cout << "EndXR OpenXR GLES session and swapchain tests: all passed.\n";
    return 0;
}
