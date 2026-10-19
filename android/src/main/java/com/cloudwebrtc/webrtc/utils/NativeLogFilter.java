package com.cloudwebrtc.webrtc.utils;

import android.util.Log;

/**
 * Keeps WebRTC's native logs (RTC_LOG) out of logcat. With libwebrtc
 * 150.7871 {@code Logging.enableLogToDebugOutput(LS_NONE)} no longer stops
 * them, and they include SDP, candidates and the E2EE keys of the frame
 * cryptor. Works from Android 11 (API 30) on; see native_log_filter.c.
 */
public final class NativeLogFilter {
  private static final String TAG = "FlutterWebRTCPlugin";

  private NativeLogFilter() {}

  /** Returns whether the filter is in place. */
  public static synchronized boolean dropWebRtcLogs() {
    try {
      System.loadLibrary("flutter_webrtc_log_filter");
      return nativeDropWebRtcLogs();
    } catch (Throwable t) {
      Log.w(TAG, "native log filter unavailable: " + t.getClass().getSimpleName());
      return false;
    }
  }

  private static native boolean nativeDropWebRtcLogs();
}
