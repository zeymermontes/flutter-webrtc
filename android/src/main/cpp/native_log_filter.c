// Keeps WebRTC's native logs out of logcat.
//
// libwebrtc 150.7871 writes every RTC_LOG from LS_INFO up to logcat whatever
// Logging.enableLogToDebugOutput() asks for: that call sets a severity the
// library no longer reads (the debug output now follows a LoggingConfig that
// is not reachable from Java). Those lines carry SDP, candidates and, from
// frame_crypto_transformer.cc, the call's E2EE keys in the clear.
//
// From Android 11 (API 30) liblog lets a process route its log messages
// through its own function: this one drops the lines whose tag is a C++
// source file (how RTC_LOG tags them on Android) and passes every other line
// to logd unchanged. Earlier Android versions have no such hook.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdatomic.h>
#include <string.h>

typedef void (*set_logger_fn)(__android_logger_function logger);

static __android_logger_function g_logd_logger = NULL;
static atomic_bool g_installed = false;

static int ends_with(const char* s, size_t len, const char* suffix) {
  size_t n = strlen(suffix);
  return len >= n && memcmp(s + len - n, suffix, n) == 0;
}

// RTC_LOG tags its lines with the file name of the call site.
static int is_webrtc_tag(const char* tag) {
  if (tag == NULL) return 0;
  size_t len = strlen(tag);
  return ends_with(tag, len, ".cc") || ends_with(tag, len, ".h") ||
         ends_with(tag, len, ".cpp");
}

static void filtering_logger(const struct __android_log_message* message) {
  if (is_webrtc_tag(message->tag)) return;
  g_logd_logger(message);
}

JNIEXPORT jboolean JNICALL
Java_com_cloudwebrtc_webrtc_utils_NativeLogFilter_nativeDropWebRtcLogs(
    JNIEnv* env, jclass clazz) {
  (void)env;
  (void)clazz;
  if (atomic_load(&g_installed)) return JNI_TRUE;
  // Looked up at run time: the library still loads on API 21-29.
  set_logger_fn set_logger =
      (set_logger_fn)dlsym(RTLD_DEFAULT, "__android_log_set_logger");
  __android_logger_function logd_logger =
      (__android_logger_function)dlsym(RTLD_DEFAULT,
                                       "__android_log_logd_logger");
  if (set_logger == NULL || logd_logger == NULL) return JNI_FALSE;
  if (atomic_exchange(&g_installed, true)) return JNI_TRUE;
  g_logd_logger = logd_logger;
  set_logger(filtering_logger);
  return JNI_TRUE;
}
