package org.webrtc.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;
import org.webrtc.EncodedImage;
import org.webrtc.VideoCodecStatus;
import org.webrtc.VideoDecoder;

public class SafeVideoDecoderTest {
  /** A decoder whose setup fails as AndroidVideoDecoder's does with no SurfaceTextureHelper. */
  private static final class Broken implements VideoDecoder {
    int releases = 0;
    int decodes = 0;
    boolean failInit = true;

    @Override
    public VideoCodecStatus initDecode(Settings settings, Callback callback) {
      if (failInit) throw new NullPointerException("SurfaceTextureHelper is null");
      return VideoCodecStatus.OK;
    }

    @Override
    public VideoCodecStatus release() {
      releases++;
      return VideoCodecStatus.OK;
    }

    @Override
    public VideoCodecStatus decode(EncodedImage frame, DecodeInfo info) {
      decodes++;
      throw new IllegalStateException("codec gone");
    }

    @Override
    public String getImplementationName() {
      return "c2.qti.avc.decoder";
    }
  }

  @Test
  public void aSetupThatThrowsAsksForTheFallbackInsteadOfAborting() {
    Broken broken = new Broken();
    VideoDecoder guarded = SafeVideoDecoder.wrap(broken);

    assertEquals(VideoCodecStatus.FALLBACK_SOFTWARE, guarded.initDecode(null, null));
    // What the failed setup left behind is let go of.
    assertEquals(1, broken.releases);
    // Not set up: nothing reaches the broken decoder.
    assertEquals(VideoCodecStatus.UNINITIALIZED, guarded.decode(null, null));
    assertEquals(0, broken.decodes);
  }

  @Test
  public void aDecodeThatThrowsIsAnErrorForThatFrameOnly() {
    Broken broken = new Broken();
    broken.failInit = false;
    VideoDecoder guarded = SafeVideoDecoder.wrap(broken);

    assertEquals(VideoCodecStatus.OK, guarded.initDecode(null, null));
    assertEquals(VideoCodecStatus.ERROR, guarded.decode(null, null));
    assertEquals(1, broken.decodes);
    assertEquals(VideoCodecStatus.OK, guarded.release());
  }

  @Test
  public void wrapsOnceAndLeavesNothingAlone() {
    Broken broken = new Broken();
    VideoDecoder guarded = SafeVideoDecoder.wrap(broken);
    assertTrue(guarded instanceof SafeVideoDecoder);
    assertSame(guarded, SafeVideoDecoder.wrap(guarded));
    assertNull(SafeVideoDecoder.wrap(null));
    assertEquals("c2.qti.avc.decoder", guarded.getImplementationName());
  }

  /** A decoder that works, counting what reaches it. */
  private static final class Counting implements VideoDecoder {
    int decodes = 0;
    VideoCodecStatus answer = VideoCodecStatus.OK;

    @Override
    public VideoCodecStatus initDecode(Settings settings, Callback callback) {
      return VideoCodecStatus.OK;
    }

    @Override
    public VideoCodecStatus release() {
      return VideoCodecStatus.OK;
    }

    @Override
    public VideoCodecStatus decode(EncodedImage frame, DecodeInfo info) {
      decodes++;
      return answer;
    }

    @Override
    public String getImplementationName() {
      return "c2.android.avc.decoder";
    }
  }

  private static EncodedImage frame(boolean key) {
    return EncodedImage.builder()
        .setBuffer(ByteBuffer.allocateDirect(1), null)
        .setFrameType(key ? EncodedImage.FrameType.VideoFrameKey
            : EncodedImage.FrameType.VideoFrameDelta)
        .createEncodedImage();
  }

  @Test
  public void aDecoderSetUpAgainStartsFromAKeyFrame() {
    Counting counting = new Counting();
    VideoDecoder guarded = SafeVideoDecoder.wrap(counting);

    assertEquals(VideoCodecStatus.OK, guarded.initDecode(null, null));
    // Delta frames first: nothing to build on, a key frame is asked for.
    assertEquals(VideoCodecStatus.ERROR, guarded.decode(frame(false), null));
    assertEquals(0, counting.decodes);
    assertEquals(VideoCodecStatus.OK, guarded.decode(frame(true), null));
    assertEquals(VideoCodecStatus.OK, guarded.decode(frame(false), null));
    assertEquals(2, counting.decodes);

    // Released and set up again (a hidden video shown again): again.
    guarded.release();
    assertEquals(VideoCodecStatus.OK, guarded.initDecode(null, null));
    assertEquals(VideoCodecStatus.ERROR, guarded.decode(frame(false), null));
    assertEquals(2, counting.decodes);
    assertEquals(VideoCodecStatus.OK, guarded.decode(frame(true), null));
    assertEquals(3, counting.decodes);
  }

  @Test
  public void aFailedDecodeWaitsForTheNextKeyFrame() {
    Counting counting = new Counting();
    VideoDecoder guarded = SafeVideoDecoder.wrap(counting);
    guarded.initDecode(null, null);
    guarded.decode(frame(true), null);

    counting.answer = VideoCodecStatus.ERROR;
    assertEquals(VideoCodecStatus.ERROR, guarded.decode(frame(false), null));
    counting.answer = VideoCodecStatus.OK;
    // The frame after the failure is a delta: held back.
    assertEquals(VideoCodecStatus.ERROR, guarded.decode(frame(false), null));
    assertEquals(2, counting.decodes);
    assertEquals(VideoCodecStatus.OK, guarded.decode(frame(true), null));
    assertEquals(3, counting.decodes);
  }
}
