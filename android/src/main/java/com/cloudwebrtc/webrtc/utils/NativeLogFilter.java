package com.cloudwebrtc.webrtc.utils;

import android.util.Log;

/**
 * Keeps WebRTC's native logs (RTC_LOG) out of logcat. With libwebrtc
 * 150.7871 {@code Logging.enableLogToDebugOutput(LS_NONE)} no longer stops
 * them, and they include SDP, candidates and the E2EE keys of the frame
 * cryptor. On every Android version the library's own calls into liblog and
 * syslog write nothing; from Android 11 (API 30) a liblog filter also drops
 * the lines tagged with a C++ source file. See native_log_filter.c.
 */
public final class NativeLogFilter {
  private static final String TAG = "FlutterWebRTCPlugin";
  private static final String WEBRTC_LIBRARY = "jingle_peerconnection_so";

  private NativeLogFilter() {}

  /** Returns whether WebRTC's native logs are kept out of logcat. */
  public static synchronized boolean dropWebRtcLogs() {
    try {
      System.loadLibrary("flutter_webrtc_log_filter");
    } catch (Throwable t) {
      Log.w(TAG, "native log filter unavailable: " + t.getClass().getSimpleName());
      return false;
    }
    boolean filtered = false;
    try {
      filtered = nativeDropWebRtcLogs();
    } catch (Throwable t) {
      Log.w(TAG, "liblog filter not installed: " + t.getClass().getSimpleName());
    }
    try {
      // Loaded here, before PeerConnectionFactory.initialize loads it again
      // (a no-op then), so its log calls are silenced before any of them runs
      // with a call's keys.
      System.loadLibrary(WEBRTC_LIBRARY);
      int silenced = nativeSilenceWebRtcLibrary();
      if (silenced > 0) return true;
      Log.w(TAG, "webrtc log calls not silenced (" + silenced + ")");
    } catch (Throwable t) {
      Log.w(TAG, "webrtc log calls not silenced: " + t.getClass().getSimpleName());
    }
    return filtered;
  }

  private static native boolean nativeDropWebRtcLogs();

  private static native int nativeSilenceWebRtcLibrary();
}
