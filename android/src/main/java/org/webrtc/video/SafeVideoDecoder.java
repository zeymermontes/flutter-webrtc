package org.webrtc.video;

import android.util.Log;

import androidx.annotation.Nullable;

import org.webrtc.EncodedImage;
import org.webrtc.VideoCodecStatus;
import org.webrtc.VideoDecoder;
import org.webrtc.WrappedNativeVideoDecoder;

/**
 * A Java video decoder whose failures never reach the native side as a
 * pending exception. libwebrtc calls {@link #initDecode}, {@link #decode}
 * and {@link #release} over JNI and aborts the whole process when one of
 * them throws. With many remote videos at once the hardware decoder can
 * fail to set up its output (SurfaceTextureHelper.create returns null when
 * no more EGL contexts or codec instances are available, and
 * AndroidVideoDecoder.initDecode then throws a NullPointerException): that
 * video is not decoded, the call goes on.
 *
 * <p>A failed setup answers {@link VideoCodecStatus#FALLBACK_SOFTWARE}, as
 * AndroidVideoDecoder does itself when MediaCodec can not be created:
 * libwebrtc then tries a software decoder when there is one, or drops that
 * stream's frames and asks for a key frame later.
 */
public class SafeVideoDecoder implements VideoDecoder {
  private static final String TAG = "SafeVideoDecoder";

  private final VideoDecoder delegate;
  private volatile boolean initialized = false;

  SafeVideoDecoder(VideoDecoder delegate) {
    this.delegate = delegate;
  }

  /**
   * [decoder] guarded, or as it is when it is not a Java decoder (a native
   * one, as the software fallback, runs in native code and throws nothing
   * into JNI) or there is none.
   */
  @Nullable
  static VideoDecoder wrap(@Nullable VideoDecoder decoder) {
    if (decoder == null
        || decoder instanceof SafeVideoDecoder
        || decoder instanceof WrappedNativeVideoDecoder) {
      return decoder;
    }
    return new SafeVideoDecoder(decoder);
  }

  @Override
  public VideoCodecStatus initDecode(Settings settings, Callback decodeCallback) {
    try {
      final VideoCodecStatus status = delegate.initDecode(settings, decodeCallback);
      initialized = status == VideoCodecStatus.OK;
      return status;
    } catch (Throwable e) {
      initialized = false;
      warn("initDecode failed in " + implementationName() + ": "
          + e.getClass().getSimpleName() + "; this video is not decoded");
      // What the failed setup left behind (its thread, a codec) goes.
      try {
        delegate.release();
      } catch (Throwable ignored) {
        // Already broken: nothing more to let go of.
      }
      return VideoCodecStatus.FALLBACK_SOFTWARE;
    }
  }

  @Override
  public VideoCodecStatus decode(EncodedImage frame, DecodeInfo info) {
    if (!initialized) return VideoCodecStatus.UNINITIALIZED;
    try {
      return delegate.decode(frame, info);
    } catch (Throwable e) {
      warn("decode failed in " + implementationName() + ": "
          + e.getClass().getSimpleName());
      return VideoCodecStatus.ERROR;
    }
  }

  @Override
  public VideoCodecStatus release() {
    initialized = false;
    try {
      return delegate.release();
    } catch (Throwable e) {
      warn("release failed in " + implementationName() + ": "
          + e.getClass().getSimpleName());
      return VideoCodecStatus.ERROR;
    }
  }

  @Override
  public String getImplementationName() {
    return implementationName();
  }

  /** Logging never makes a guard throw (nor a JVM test, where Log is a stub). */
  private static void warn(String message) {
    try {
      Log.w(TAG, message);
    } catch (Throwable ignored) {
      // Nothing else to tell it with.
    }
  }

  private String implementationName() {
    try {
      return delegate.getImplementationName();
    } catch (Throwable e) {
      return "unknown";
    }
  }
}
