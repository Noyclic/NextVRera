# APK import and inspection

QuestPhoneXR can select an APK through Android's Storage Access Framework, copy it into
the app-private `files/imported-apks` directory, and keep a local index of imported packages.

The archive inspector reads package name, label, version, min/target Android API, required
hardware features, native library ABIs, and Quest/Meta-related metadata keys. The library
shows conservative manifest checks for a newer minSdk, a native ABI mismatch, missing
required device features, and likely Quest/Meta-specific packaging.

This is inspection only:
- No APK is installed by importing it.
- No imported code is executed.
- A clean manifest report does not prove launchability, graphics compatibility, tracking,
  controls, or performance.
- Quest-only applications still require a compatible runtime or a real port. A normal
  Android activity cannot turn an arbitrary Quest APK into a phone-native VR game.

Imported APKs remain private to QuestPhoneXR. Deleting an entry removes both its metadata
and the copied archive. Importing another version of the same package replaces the prior
local copy.


## Optional Android installation

Imported packages can be handed to Android's system package installer using a temporary
FileProvider content URI. On Android 8 and newer, the user may first need to authorize
QuestPhoneXR as an allowed source for app installs. Android then shows its own confirmation
and handles signature conflicts, package validation, and the actual installation.

This action installs a normal Android-compatible APK into Android. It does not install the
package into a separate QuestPhoneXR runtime, does not emulate a Quest headset, and does not
make Quest-only applications launchable on a phone. Manifest inspection warnings should be
reviewed before proceeding.


## Native XR and graphics hints

The compatibility audit also scans packaged `lib/**/*.so` files for a bounded amount of
read-only string data (up to 16 MiB per library and 64 MiB total). It looks for OpenXR
extension-name strings and common Vulkan / OpenGL ES / EGL symbols. The bundled
`libopenxr_loader.so` is excluded so the loader's own extension catalogue does not make
every APK appear to use every extension.

These are **static hints only**. A string can be present in an engine or unused plugin even
when the game never calls that API. It does not prove that an extension is required, that
the game selects Vulkan instead of OpenGL ES, or that a feature is active. EndXR currently
advertises `XR_KHR_android_create_instance`, `XR_KHR_opengl_es_enable` and
`XR_EXT_hand_tracking`; it does not yet expose a Vulkan graphics binding or Meta-specific
runtime extensions. A game that requires an unadvertised extension or selects Vulkan as its
only graphics backend may fail before rendering. The audit calls this out before launch
without automatically blocking a game based on uncertain string matches.
