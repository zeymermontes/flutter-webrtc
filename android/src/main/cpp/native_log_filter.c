// Keeps WebRTC's native logs out of logcat.
//
// libwebrtc 150.7871 writes every RTC_LOG from LS_INFO up to logcat whatever
// Logging.enableLogToDebugOutput() asks for: that call sets a severity the
// library no longer reads (the debug output now follows a LoggingConfig that
// is not reachable from Java). Those lines carry SDP, candidates and, from
// frame_crypto_transformer.cc, the call's E2EE keys in the clear.
//
// Two layers, both installed before the factory starts:
//
// 1. Every Android version (7 on): libjingle_peerconnection_so.so reaches
//    logcat only through its own imports of liblog (__android_log_print) and
//    libc (syslog). Their slots in the library's GOT are pointed at
//    functions that write nothing, so its lines never leave the process. Only
//    that library's calls change; the rest of the process logs as before.
//
// 2. Android 11 (API 30) on: liblog lets a process route its log messages
//    through its own function. This one drops the lines whose tag is a C++
//    source file (how RTC_LOG tags them on Android) and passes every other
//    line to logd unchanged, in case another copy of libwebrtc shows up.

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

// ---------------------------------------------------------------------------
// 2. liblog logger (API 30+)

typedef void (*set_logger_fn)(__android_logger_function logger);

static __android_logger_function g_logd_logger = NULL;
static atomic_bool g_logger_installed = false;

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

static int install_liblog_logger(void) {
  if (atomic_load(&g_logger_installed)) return 1;
  // Looked up at run time: the library still loads on API 24-29.
  set_logger_fn set_logger =
      (set_logger_fn)dlsym(RTLD_DEFAULT, "__android_log_set_logger");
  __android_logger_function logd_logger =
      (__android_logger_function)dlsym(RTLD_DEFAULT,
                                       "__android_log_logd_logger");
  if (set_logger == NULL || logd_logger == NULL) return 0;
  if (atomic_exchange(&g_logger_installed, true)) return 1;
  g_logd_logger = logd_logger;
  set_logger(filtering_logger);
  return 1;
}

// ---------------------------------------------------------------------------
// 1. GOT slots of libjingle_peerconnection_so.so (every version)

static int silent_log_print(int prio, const char* tag, const char* fmt, ...) {
  (void)prio;
  (void)tag;
  (void)fmt;
  return 0;
}

static int silent_log_write(int prio, const char* tag, const char* text) {
  (void)prio;
  (void)tag;
  (void)text;
  return 0;
}

static int silent_log_vprint(int prio, const char* tag, const char* fmt,
                             va_list ap) {
  (void)prio;
  (void)tag;
  (void)fmt;
  (void)ap;
  return 0;
}

static int silent_log_buf_print(int buf, int prio, const char* tag,
                                const char* fmt, ...) {
  (void)buf;
  (void)prio;
  (void)tag;
  (void)fmt;
  return 0;
}

static void silent_syslog(int priority, const char* fmt, ...) {
  (void)priority;
  (void)fmt;
}

static void silent_vsyslog(int priority, const char* fmt, va_list ap) {
  (void)priority;
  (void)fmt;
  (void)ap;
}

struct replacement {
  const char* name;
  void* fn;
};

static const struct replacement kReplacements[] = {
    {"__android_log_print", (void*)silent_log_print},
    {"__android_log_write", (void*)silent_log_write},
    {"__android_log_vprint", (void*)silent_log_vprint},
    {"__android_log_buf_print", (void*)silent_log_buf_print},
    {"syslog", (void*)silent_syslog},
    {"vsyslog", (void*)silent_vsyslog},
};

static const char kWebRtcLibrary[] = "libjingle_peerconnection_so.so";

#if defined(__LP64__)
typedef Elf64_Rela plt_rel;
#define REL_SYM(info) ELF64_R_SYM(info)
#define REL_TYPE(info) ELF64_R_TYPE(info)
#else
typedef Elf32_Rel plt_rel;
#define REL_SYM(info) ELF32_R_SYM(info)
#define REL_TYPE(info) ELF32_R_TYPE(info)
#endif

#if defined(__aarch64__)
#define IS_SLOT_RELOC(t) \
  ((t) == R_AARCH64_JUMP_SLOT || (t) == R_AARCH64_GLOB_DAT || (t) == R_AARCH64_ABS64)
#elif defined(__arm__)
#define IS_SLOT_RELOC(t) \
  ((t) == R_ARM_JUMP_SLOT || (t) == R_ARM_GLOB_DAT || (t) == R_ARM_ABS32)
#elif defined(__x86_64__)
#define IS_SLOT_RELOC(t) \
  ((t) == R_X86_64_JUMP_SLOT || (t) == R_X86_64_GLOB_DAT || (t) == R_X86_64_64)
#elif defined(__i386__)
#define IS_SLOT_RELOC(t) \
  ((t) == R_386_JMP_SLOT || (t) == R_386_GLOB_DAT || (t) == R_386_32)
#else
#define IS_SLOT_RELOC(t) 0
#endif

struct hook_result {
  int found;    // the library is loaded
  int patched;  // slots now pointing at a silent function
  int failed;   // slots that could not be written
};

static int name_is_webrtc(const char* path) {
  if (path == NULL) return 0;
  const char* base = strrchr(path, '/');
  base = base == NULL ? path : base + 1;
  return strcmp(base, kWebRtcLibrary) == 0;
}

// Points one GOT slot at [fn]. The slot sits in the RELRO segment (read-only
// once the linker is done) or in plain data; it is made writable for the
// write and given back the protection it had.
static int write_slot(uintptr_t slot, void* fn, uintptr_t relro_start,
                      uintptr_t relro_end) {
  if (*(void**)slot == fn) return 1;
  long page = sysconf(_SC_PAGESIZE);
  if (page <= 0) page = 4096;
  uintptr_t start = slot & ~((uintptr_t)page - 1);
  int in_relro = slot >= relro_start && slot < relro_end;
  if (mprotect((void*)start, (size_t)page, PROT_READ | PROT_WRITE) != 0) {
    return 0;
  }
  __atomic_store_n((void**)slot, fn, __ATOMIC_SEQ_CST);
  if (in_relro) mprotect((void*)start, (size_t)page, PROT_READ);
  return 1;
}

static void patch_table(uintptr_t base, const plt_rel* rel, size_t count,
                        const ElfW(Sym) * symtab, const char* strtab,
                        uintptr_t relro_start, uintptr_t relro_end,
                        struct hook_result* result) {
  for (size_t i = 0; i < count; i++) {
    unsigned type = (unsigned)REL_TYPE(rel[i].r_info);
    unsigned sym = (unsigned)REL_SYM(rel[i].r_info);
    if (sym == 0 || !IS_SLOT_RELOC(type)) continue;
    const char* name = strtab + symtab[sym].st_name;
    for (size_t r = 0; r < sizeof(kReplacements) / sizeof(kReplacements[0]);
         r++) {
      if (strcmp(name, kReplacements[r].name) != 0) continue;
      uintptr_t slot = base + rel[i].r_offset;
      if (write_slot(slot, kReplacements[r].fn, relro_start, relro_end)) {
        result->patched++;
      } else {
        result->failed++;
      }
      break;
    }
  }
}

// The dynamic section's addresses are link-time ones on Android; a loader
// that rewrote them would leave them above the load base.
static uintptr_t dyn_addr(uintptr_t base, ElfW(Addr) value) {
  return value >= base ? (uintptr_t)value : base + (uintptr_t)value;
}

static int patch_library(struct dl_phdr_info* info, size_t size, void* data) {
  (void)size;
  struct hook_result* result = (struct hook_result*)data;
  if (!name_is_webrtc(info->dlpi_name)) return 0;
  result->found = 1;

  uintptr_t base = (uintptr_t)info->dlpi_addr;
  const ElfW(Dyn)* dynamic = NULL;
  uintptr_t relro_start = 0, relro_end = 0;
  for (int i = 0; i < info->dlpi_phnum; i++) {
    const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
    if (ph->p_type == PT_DYNAMIC) {
      dynamic = (const ElfW(Dyn)*)(base + ph->p_vaddr);
    } else if (ph->p_type == PT_GNU_RELRO) {
      relro_start = base + ph->p_vaddr;
      relro_end = relro_start + ph->p_memsz;
    }
  }
  if (dynamic == NULL) return 1;

  const ElfW(Sym)* symtab = NULL;
  const char* strtab = NULL;
  uintptr_t jmprel = 0, rel = 0;
  size_t jmprel_size = 0, rel_size = 0;
  for (const ElfW(Dyn)* d = dynamic; d->d_tag != DT_NULL; d++) {
    switch (d->d_tag) {
      case DT_SYMTAB:
        symtab = (const ElfW(Sym)*)dyn_addr(base, d->d_un.d_ptr);
        break;
      case DT_STRTAB:
        strtab = (const char*)dyn_addr(base, d->d_un.d_ptr);
        break;
      case DT_JMPREL:
        jmprel = dyn_addr(base, d->d_un.d_ptr);
        break;
      case DT_PLTRELSZ:
        jmprel_size = (size_t)d->d_un.d_val;
        break;
#if defined(__LP64__)
      case DT_RELA:
        rel = dyn_addr(base, d->d_un.d_ptr);
        break;
      case DT_RELASZ:
        rel_size = (size_t)d->d_un.d_val;
        break;
#else
      case DT_REL:
        rel = dyn_addr(base, d->d_un.d_ptr);
        break;
      case DT_RELSZ:
        rel_size = (size_t)d->d_un.d_val;
        break;
#endif
      default:
        break;
    }
  }
  if (symtab == NULL || strtab == NULL) return 1;

  if (jmprel != 0 && jmprel_size > 0) {
    patch_table(base, (const plt_rel*)jmprel, jmprel_size / sizeof(plt_rel),
                symtab, strtab, relro_start, relro_end, result);
  }
  if (rel != 0 && rel_size > 0) {
    patch_table(base, (const plt_rel*)rel, rel_size / sizeof(plt_rel), symtab,
                strtab, relro_start, relro_end, result);
  }
  return 1;
}

static pthread_mutex_t g_hook_lock = PTHREAD_MUTEX_INITIALIZER;

// Number of slots silenced in libjingle_peerconnection_so.so, -1 when the
// library is not loaded, -2 when a slot could not be written.
static int silence_webrtc_library(void) {
  struct hook_result result = {0, 0, 0};
  pthread_mutex_lock(&g_hook_lock);
  dl_iterate_phdr(patch_library, &result);
  pthread_mutex_unlock(&g_hook_lock);
  if (!result.found) return -1;
  if (result.failed > 0) return -2;
  return result.patched;
}

JNIEXPORT jint JNICALL
Java_com_cloudwebrtc_webrtc_utils_NativeLogFilter_nativeSilenceWebRtcLibrary(
    JNIEnv* env, jclass clazz) {
  (void)env;
  (void)clazz;
  return silence_webrtc_library();
}

JNIEXPORT jboolean JNICALL
Java_com_cloudwebrtc_webrtc_utils_NativeLogFilter_nativeDropWebRtcLogs(
    JNIEnv* env, jclass clazz) {
  (void)env;
  (void)clazz;
  return install_liblog_logger() ? JNI_TRUE : JNI_FALSE;
}
