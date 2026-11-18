#include <jni.h>
#include <dlfcn.h>
#include <link.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <dirent.h>

#include <cstdlib>
#include <cstring>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace {

std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void append_property(std::ostringstream& report, const char* name) {
    char value[PROP_VALUE_MAX] = {};
    const int length = __system_property_get(name, value);
    report << name << "=" << (length > 0 ? value : "<not exposed>") << "\n";
}

void append_environment(std::ostringstream& report, const char* name) {
    const char* value = std::getenv(name);
    report << name << "=" << ((value != nullptr && value[0] != '\0') ? value : "<unset>") << "\n";
}

void append_path_status(std::ostringstream& report, const char* path) {
    struct stat info {};
    if (stat(path, &info) != 0) {
        report << path << "=NOT_FOUND_OR_NOT_STATABLE\n";
        return;
    }

    report << path
           << "=PRESENT mode=0" << std::oct << (info.st_mode & 07777) << std::dec
           << " uid=" << info.st_uid
           << " gid=" << info.st_gid
           << " readable=" << (access(path, R_OK) == 0 ? "yes" : "no")
           << " writable=" << (access(path, W_OK) == 0 ? "yes" : "no")
           << " executable=" << (access(path, X_OK) == 0 ? "yes" : "no")
           << "\n";
}

void append_directory_entries(std::ostringstream& report, const std::string& path) {
    report << "Directory " << path << ": ";
    DIR* directory = opendir(path.c_str());
    if (directory == nullptr) {
        report << "NOT_OPENABLE\n";
        return;
    }

    std::vector<std::string> entries;
    while (dirent* entry = readdir(directory)) {
        const std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        if (name.find("Qnn") != std::string::npos ||
            name.find("qnn") != std::string::npos ||
            name.find("dsp") != std::string::npos ||
            name.find("Dsp") != std::string::npos ||
            name.find("Htp") != std::string::npos ||
            name.find("Skel") != std::string::npos ||
            name.find("Stub") != std::string::npos) {
            entries.push_back(name);
        }
    }
    closedir(directory);

    if (entries.empty()) {
        report << "no QNN/DSP/HTP entries\n";
        return;
    }
    for (size_t i = 0; i < entries.size(); ++i) {
        if (i != 0) report << ", ";
        report << entries[i];
    }
    report << "\n";
}

void append_symbol_probe(std::ostringstream& report,
                         const char* library,
                         const std::vector<const char*>& symbols) {
    dlerror();
    void* handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    const char* open_error = dlerror();
    report << library << ": ";
    if (handle == nullptr) {
        report << "OPEN_FAILED (" << (open_error != nullptr ? open_error : "unknown") << ")\n";
        return;
    }

    report << "OPEN_OK";
    for (const char* symbol : symbols) {
        dlerror();
        void* address = dlsym(handle, symbol);
        const char* symbol_error = dlerror();
        report << "\n  " << symbol << "="
               << ((address != nullptr && symbol_error == nullptr) ? "EXPORTED" : "not-exported");
    }
    report << "\n";
    dlclose(handle);
}

struct LoadedModuleContext {
    std::ostringstream* report;
    size_t count;
};

int loaded_module_callback(struct dl_phdr_info* info, size_t, void* opaque) {
    auto* context = static_cast<LoadedModuleContext*>(opaque);
    const char* raw_name = info->dlpi_name;
    if (raw_name == nullptr || raw_name[0] == '\0') return 0;

    const std::string name(raw_name);
    if (name.find("Qnn") == std::string::npos &&
        name.find("qnn") == std::string::npos &&
        name.find("rpc") == std::string::npos &&
        name.find("Rpc") == std::string::npos &&
        name.find("adsprpc") == std::string::npos &&
        name.find("fast") == std::string::npos) {
        return 0;
    }

    *context->report << name << "\n";
    context->count++;
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_hardwareprobe_MainActivity_probeNativeLibraries(JNIEnv* env, jobject) {
    const std::vector<std::string> libraries = {
        "libcdsprpc.so",
        "libQnnSystem.so",
        "libQnnDsp.so",
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_hardwareprobe_MainActivity_probeNativeRuntimeEnvironment(
    JNIEnv* env, jobject, jstring native_library_dir, jstring skel_library_dir) {
    const std::string native_dir = jstring_to_string(env, native_library_dir);
    const std::string skel_dir = jstring_to_string(env, skel_library_dir);

    std::ostringstream report;
    report << "Process UID=" << getuid() << " PID=" << getpid() << "\n";
    report << "nativeLibraryDir=" << native_dir << "\n";
    report << "selectedSkelLibraryDir=" << skel_dir << "\n";

    report << "\n[QNN / Qualcomm system properties]\n";
    append_property(report, "ro.soc.model");
    append_property(report, "ro.board.platform");
    append_property(report, "ro.hardware");
    append_property(report, "ro.boot.hardware");
    append_property(report, "ro.vendor.qti.soc_id");
    append_property(report, "ro.vendor.qti.soc_model");
    append_property(report, "ro.vendor.qti.soc_hw_version");
    append_property(report, "ro.vendor.product.device");

    report << "\n[Native environment]\n";
    append_environment(report, "ADSP_LIBRARY_PATH");
    append_environment(report, "LD_LIBRARY_PATH");
    append_environment(report, "ANDROID_DATA");

    report << "\n[Native library directories]\n";
    append_directory_entries(report, native_dir);
    append_directory_entries(report, skel_dir);

    report << "\n[FastRPC device nodes]\n";
    const char* device_nodes[] = {
        "/dev/fastrpc-cdsp",
        "/dev/fastrpc-cdsp-secure",
        "/dev/fastrpc-cdsp-smd",
        "/dev/fastrpc-adsp",
        "/dev/fastrpc-adsp-secure",
        "/dev/fastrpc-adsp-smd",
        "/dev/adsprpc-smd",
        "/dev/fastrpc-smd"
    };
    for (const char* path : device_nodes) append_path_status(report, path);

    report << "\n[Qualcomm DSP firmware / skeleton search locations]\n";
    const char* dsp_directories[] = {
        "/vendor/lib/rfsa/adsp",
        "/vendor/lib64/rfsa/adsp",
        "/vendor/dsp",
        "/dsp",
        "/vendor/firmware",
        "/system/vendor/firmware"
    };
    for (const char* path : dsp_directories) append_path_status(report, path);

    report << "\n[QNN native API exports]\n";
    append_symbol_probe(report, "libQnnSystem.so",
                        {"QnnSystemInterface_getProviders", "QnnSystemLog_create", "QnnLog_create"});
    append_symbol_probe(report, "libQnnDsp.so",
                        {"QnnInterface_getProviders", "QnnBackend_create", "QnnDevice_create", "QnnLog_create"});
    append_symbol_probe(report, "libQnnHtp.so",
                        {"QnnInterface_getProviders", "QnnBackend_create", "QnnDevice_create", "QnnLog_create"});
    append_symbol_probe(report, "libQnnDspV66Stub.so",
                        {"QnnDspV66Stub", "QnnInterface_getProviders"});
    append_symbol_probe(report, "libQnnHtpV68Stub.so",
                        {"QnnHtpV68Stub", "QnnInterface_getProviders"});

    const std::string value = report.str();
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_hardwareprobe_MainActivity_snapshotLoadedQnnModules(
    JNIEnv* env, jobject, jstring skel_library_dir) {
    const std::string skel_dir = jstring_to_string(env, skel_library_dir);
    std::ostringstream report;
    report << "Selected skel directory at snapshot: " << skel_dir << "\n";

    LoadedModuleContext context{&report, 0};
    dl_iterate_phdr(loaded_module_callback, &context);
    if (context.count == 0) {
        report << "No matching QNN/FastRPC modules found in the process link map.\n";
    } else {
        report << "Matching loaded module count: " << context.count << "\n";
    }

    const std::string value = report.str();
    return env->NewStringUTF(value.c_str());
}
