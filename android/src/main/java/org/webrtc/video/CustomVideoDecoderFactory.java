package org.webrtc.video;

import androidx.annotation.Nullable;

import org.webrtc.EglBase;
import org.webrtc.HardwareVideoDecoderFactory;
import org.webrtc.PlatformSoftwareVideoDecoderFactory;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoDecoder;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoDecoderFallback;
import org.webrtc.WrappedVideoDecoderFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomVideoDecoderFactory implements VideoDecoderFactory {
    private SoftwareVideoDecoderFactory softwareVideoDecoderFactory = new SoftwareVideoDecoderFactory();
    private WrappedVideoDecoderFactory wrappedVideoDecoderFactory;
    // The factories WrappedVideoDecoderFactory picks from, used here so each
    // Java decoder is guarded (SafeVideoDecoder) before it goes into the
    // software fallback: a decoder that throws over JNI aborts the process.
    private final VideoDecoderFactory hardwareFactory;
    private final VideoDecoderFactory hardwareFactoryWithoutEglContext;
    private final VideoDecoderFactory platformSoftwareFactory;
    private boolean forceSWCodec  = false;

    private List<String> forceSWCodecs = new ArrayList<>();

    public  CustomVideoDecoderFactory(EglBase.Context sharedContext) {
        this.wrappedVideoDecoderFactory = new WrappedVideoDecoderFactory(sharedContext);
        this.hardwareFactory = new HardwareVideoDecoderFactory(sharedContext);
        this.hardwareFactoryWithoutEglContext = new HardwareVideoDecoderFactory(null);
        this.platformSoftwareFactory = new PlatformSoftwareVideoDecoderFactory(sharedContext);
    }

    public void setForceSWCodec(boolean forceSWCodec) {
        this.forceSWCodec = forceSWCodec;
    }

    public void setForceSWCodecList(List<String> forceSWCodecs) {
        this.forceSWCodecs = forceSWCodecs;
    }

    @Nullable
    @Override
    public VideoDecoder createDecoder(VideoCodecInfo videoCodecInfo) {
        if(forceSWCodec) {
            return softwareVideoDecoderFactory.createDecoder(videoCodecInfo);
        }
        if(!forceSWCodecs.isEmpty()) {
            if(forceSWCodecs.contains(videoCodecInfo.name)) {
                return softwareVideoDecoderFactory.createDecoder(videoCodecInfo);
            }
        }
        return createGuardedDecoder(videoCodecInfo);
    }

    /**
     * What WrappedVideoDecoderFactory.createDecoder picks (the hardware
     * decoder, with the software or the platform's software one behind it),
     * each Java decoder guarded by SafeVideoDecoder: with many videos at once
     * the hardware decoder's setup can throw (no SurfaceTextureHelper), and an
     * exception over JNI aborts the process instead of failing that video.
     */
    @Nullable
    private VideoDecoder createGuardedDecoder(VideoCodecInfo info) {
        VideoDecoder software = softwareVideoDecoderFactory.createDecoder(info);
        VideoDecoder hardware = hardwareFactory.createDecoder(info);
        if (software == null) {
            software = platformSoftwareFactory.createDecoder(info);
        }
        if (hardware != null && disableSurfaceTextureFrame(hardware.getImplementationName())) {
            hardware.release();
            hardware = hardwareFactoryWithoutEglContext.createDecoder(info);
        }
        software = SafeVideoDecoder.wrap(software);
        hardware = SafeVideoDecoder.wrap(hardware);
        if (hardware != null && software != null) {
            return new VideoDecoderFallback(software, hardware);
        }
        return hardware != null ? hardware : software;
    }

    private static boolean disableSurfaceTextureFrame(String name) {
        return name.startsWith("OMX.qcom.") || name.startsWith("OMX.hisi.");
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        VideoCodecInfo[] codecs;
        if(forceSWCodec && forceSWCodecs.isEmpty()) {
            codecs = softwareVideoDecoderFactory.getSupportedCodecs();
        } else {
            codecs = wrappedVideoDecoderFactory.getSupportedCodecs();
        }

        return withHigherH264Level(codecs);
    }

    private VideoCodecInfo[] withHigherH264Level(VideoCodecInfo[] codecs) {
        VideoCodecInfo[] adjustedCodecs = new VideoCodecInfo[codecs.length];
        for (int i = 0; i < codecs.length; i++) {
            VideoCodecInfo codec = codecs[i];
            if (!"H264".equalsIgnoreCase(codec.name)) {
                adjustedCodecs[i] = codec;
                continue;
            }

            String profileLevelId = codec.params.get("profile-level-id");
            if (profileLevelId == null || profileLevelId.length() != 6) {
                adjustedCodecs[i] = codec;
                continue;
            }

            String profile = profileLevelId.substring(0, 4);
            String level = profileLevelId.substring(4);
            if (Integer.parseInt(level, 16) >= 0x29) {
                adjustedCodecs[i] = codec;
                continue;
            }

            Map<String, String> params = new HashMap<>(codec.params);
            params.put("profile-level-id", profile + "29");
            adjustedCodecs[i] = new VideoCodecInfo(codec.name, params, codec.scalabilityModes);
        }

        return adjustedCodecs;
    }
}
