package org.webrtc.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
}
