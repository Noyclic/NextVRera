#include <jni.h>
#include <dlfcn.h>

#include <sstream>
#include <string>
#include <vector>

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_hardwareprobe_MainActivity_probeNativeLibraries(JNIEnv* env, jobject) {
    const std::vector<std::string> libraries = {
        "libcdsprpc.so",
        "libQnnSystem.so",
        "libQnnDsp.so",
        "libQnnDspV66Skel.so",
        "libQnnDspV66Stub.so",
        "libQnnHtp.so",
        "libQnnHtpPrepare.so",
        "libQnnHtpV68Stub.so",
        "libQnnHtpV69Stub.so",
        "libQnnHtpV73Stub.so",
        "libQnnHtpV75Stub.so",
        "libQnnHtpV79Stub.so",
        "libQnnHtpV81Stub.so",
        "libOpenCL.so",
        "libvulkan.so"
    };

    std::ostringstream report;
    for (size_t i = 0; i < libraries.size(); ++i) {
        const char* name = libraries[i].c_str();
        dlerror();
        void* handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        const char* error = dlerror();
        if (handle != nullptr) {
            report << name << "=LOADABLE";
            dlclose(handle);
        } else {
            report << name << "=NOT_LOADABLE";
            if (error != nullptr) {
                std::string message(error);
                if (message.size() > 180) message.resize(180);
                report << " (" << message << ")";
            }
        }
        if (i + 1 < libraries.size()) report << "\n";
    }
    const std::string value = report.str();
    return env->NewStringUTF(value.c_str());
}
