package com.cloudwebrtc.webrtc;

import android.graphics.SurfaceTexture;
import android.view.Surface;

import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.GlRectDrawer;
import org.webrtc.RendererCommon;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoFrame;

import java.util.concurrent.CountDownLatch;

import io.flutter.view.TextureRegistry;

/**
 * Display the video stream on a Surface.
 * renderFrame() is asynchronous to avoid blocking the calling thread.
 * This class is thread safe and handles access from potentially three different threads:
 * Interaction from the main app in init, release and setMirror.
 * Interaction from C++ rtc::VideoSinkInterface in renderFrame.
 * Interaction from SurfaceHolder lifecycle in surfaceCreated, surfaceChanged, and surfaceDestroyed.
 */
public class SurfaceTextureRenderer extends EglRenderer {
  /**
   * Told, on the frame delivery thread, that frames no longer match the size
   * of the surface they are drawn into. The surface is not swapped in place:
   * the owner sets the renderer up again (release, init, surfaceCreated) so
   * the next frame gets a surface of the new size in a fresh EGL context.
   */
  public interface SurfaceSizeListener {
    void onSurfaceSizeChanged(int width, int height);
  }

  private volatile SurfaceSizeListener surfaceSizeListener;

  public void setSurfaceSizeListener(SurfaceSizeListener listener) {
    surfaceSizeListener = listener;
  }

  // Callback for reporting renderer events. Read-only after initilization so no lock required.
  private RendererCommon.RendererEvents rendererEvents;
  private final Object layoutLock = new Object();
  private boolean isRenderingPaused;
  private boolean isFirstFrameRendered;
  private int rotatedFrameWidth;
  private int rotatedFrameHeight;
  private int frameRotation;

  /**
   * In order to render something, you must first call init().
   */
  public SurfaceTextureRenderer(String name) {
    super(name);
  }

  public void init(final EglBase.Context sharedContext,
                   RendererCommon.RendererEvents rendererEvents) {
    init(sharedContext, rendererEvents, EglBase.CONFIG_PLAIN, new GlRectDrawer());
  }

  /**
   * Initialize this class, sharing resources with |sharedContext|. The custom |drawer| will be used
   * for drawing frames on the EGLSurface. This class is responsible for calling release() on
   * |drawer|. It is allowed to call init() to reinitialize the renderer after a previous
   * init()/release() cycle.
   */
  public void init(final EglBase.Context sharedContext,
                   RendererCommon.RendererEvents rendererEvents, final int[] configAttributes,
                   RendererCommon.GlDrawer drawer) {
    ThreadUtils.checkIsOnMainThread();
    this.rendererEvents = rendererEvents;
    synchronized (layoutLock) {
      isFirstFrameRendered = false;
      rotatedFrameWidth = 0;
      rotatedFrameHeight = 0;
      frameRotation = -1;
    }
    super.init(sharedContext, configAttributes, drawer);
  }
  @Override
  public void init(final EglBase.Context sharedContext, final int[] configAttributes,
                   RendererCommon.GlDrawer drawer) {
    init(sharedContext, null /* rendererEvents */, configAttributes, drawer);
  }
  /**
   * Limit render framerate.
   *
   * @param fps Limit render framerate to this value, or use Float.POSITIVE_INFINITY to disable fps
   *            reduction.
   */
  @Override
  public void setFpsReduction(float fps) {
    synchronized (layoutLock) {
      isRenderingPaused = fps == 0f;
    }
    super.setFpsReduction(fps);
  }
  @Override
  public void disableFpsReduction() {
    synchronized (layoutLock) {
      isRenderingPaused = false;
    }
    super.disableFpsReduction();
  }
  @Override
  public void pauseVideo() {
    synchronized (layoutLock) {
      isRenderingPaused = true;
    }
    super.pauseVideo();
  }
  // VideoSink interface.
  @Override
  public void onFrame(VideoFrame frame) {
    SurfaceSizeListener sizeListener = null;
    synchronized (surfaceLock) {
      if(surface == null) {
        surfaceWidth = frame.getRotatedWidth();
        surfaceHeight = frame.getRotatedHeight();
        sizeChangeReported = false;
        producer.setSize(surfaceWidth, surfaceHeight);
        surface = producer.getSurface();
        createEglSurface(surface);
      } else if (!sizeChangeReported && frameSizeChanged(frame)) {
        // The producer's backing buffers are fixed-size: setSize() only takes
        // effect for a Surface obtained afterwards. Swapping the EGL surface of
        // this renderer onto that new Surface in place left the picture frozen
        // on some devices (frames kept being rendered, the texture never
        // updated). Instead the size is set here and the owner sets the whole
        // renderer up again, the same sequence as binding a track, so the next
        // frame obtains the new Surface in a fresh EGL context. Until then
        // frames keep going to the current surface, scaled.
        producer.setSize(frame.getRotatedWidth(), frame.getRotatedHeight());
        sizeChangeReported = true;
        sizeListener = surfaceSizeListener;
      }
    }
    if (sizeListener != null) {
      sizeListener.onSurfaceSizeChanged(
          frame.getRotatedWidth(), frame.getRotatedHeight());
    }
    updateFrameDimensionsAndReportEvents(frame);
    super.onFrame(frame);
  }

  // Whether [frame] no longer matches the surface it would be drawn into.
  // Called with surfaceLock held.
  private boolean frameSizeChanged(VideoFrame frame) {
    synchronized (layoutLock) {
      if (isRenderingPaused) {
        return false;
      }
    }
    return surfaceWidth != frame.getRotatedWidth()
        || surfaceHeight != frame.getRotatedHeight();
  }

  /**
   * Also forgets the Surface: after init() the first frame obtains the
   * producer's current Surface (of the size last set) and creates the EGL
   * surface on it.
   */
  @Override
  public void release() {
    super.release();
    synchronized (surfaceLock) {
      // init() posts the EGL surface creation again with the last Surface
      // given to createEglSurface(), which would be the old size's. With the
      // render thread gone this only clears it, synchronously.
      releaseEglSurface(() -> {});
      surface = null;
    }
  }

  // Guards surface lifecycle transitions: creation/recreation happens on the
  // frame delivery thread while destruction arrives on the main thread via
  // the producer's onSurfaceCleanup callback. Serializing the two prevents a
  // frame from re-creating the EGL surface against a Surface the producer is
  // concurrently invalidating. surfaceDestroyed() blocks on the EGL release
  // while holding this lock; the latch is signaled by the EglRenderer render
  // thread, which never acquires it, so the wait cannot deadlock.
  private final Object surfaceLock = new Object();
  private Surface surface = null;

  // Rotated size the current Surface was obtained for, and whether a change
  // from it was already reported to the SurfaceSizeListener. Guarded by
  // surfaceLock.
  private int surfaceWidth;
  private int surfaceHeight;
  private boolean sizeChangeReported;

  private TextureRegistry.SurfaceProducer producer;

  public void surfaceCreated(final TextureRegistry.SurfaceProducer producer) {
    ThreadUtils.checkIsOnMainThread();
    this.producer = producer;
    this.producer.setCallback(
            new TextureRegistry.SurfaceProducer.Callback() {
              @Override
              public void onSurfaceAvailable() {
                // Do surface initialization here, and draw the current frame.
              }

              @Override
              public void onSurfaceCleanup() {
                surfaceDestroyed();
              }
            }
    );
  }

  public void surfaceDestroyed() {
    ThreadUtils.checkIsOnMainThread();
    synchronized (surfaceLock) {
      final CountDownLatch completionLatch = new CountDownLatch(1);
      releaseEglSurface(completionLatch::countDown);
      ThreadUtils.awaitUninterruptibly(completionLatch);
      surface = null;
    }
  }

  // Update frame dimensions and report any changes to |rendererEvents|.
  private void updateFrameDimensionsAndReportEvents(VideoFrame frame) {
    synchronized (layoutLock) {
      if (isRenderingPaused) {
        return;
      }
      if (!isFirstFrameRendered) {
        isFirstFrameRendered = true;
        if (rendererEvents != null) {
          rendererEvents.onFirstFrameRendered();
        }
      }
      if (rotatedFrameWidth != frame.getRotatedWidth()
              || rotatedFrameHeight != frame.getRotatedHeight()
              || frameRotation != frame.getRotation()) {
        if (rendererEvents != null) {
          rendererEvents.onFrameResolutionChanged(
                  frame.getBuffer().getWidth(), frame.getBuffer().getHeight(), frame.getRotation());
        }
        rotatedFrameWidth = frame.getRotatedWidth();
        rotatedFrameHeight = frame.getRotatedHeight();
        frameRotation = frame.getRotation();
      }
    }
  }
}
