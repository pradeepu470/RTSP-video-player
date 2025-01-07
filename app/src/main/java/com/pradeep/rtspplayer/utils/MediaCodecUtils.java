package com.pradeep.rtspplayer.utils;


import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@SuppressLint("UnsafeOptInUsageError")
public class MediaCodecUtils {
    private static final HashMap<String, List<MediaCodecInfo>> decoderInfosMap = new HashMap<>();
    private static final String TAG = MediaCodecUtils.class.getSimpleName();

    private static List<MediaCodecInfo> getDecoderInfos(String mimeType) {
        List<MediaCodecInfo> list = decoderInfosMap.get(mimeType);
        if (list == null || list.isEmpty()) {
            List<MediaCodecInfo> decoderInfos = new ArrayList<>();
            try {
                decoderInfos = MediaCodecUtil.getDecoderInfos(mimeType, false, false);
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize '" + mimeType + "' decoders list (" + e.getMessage() + ")", e);
            }
            decoderInfosMap.put(mimeType, decoderInfos);
            return decoderInfos;
        } else {
            return list;
        }
    }
    public synchronized static List<MediaCodecInfo> getSoftwareDecoders(String mimeType) {
        List<MediaCodecInfo> decoderInfos = getDecoderInfos(mimeType);
        List<MediaCodecInfo> list = new ArrayList<>();
        for (MediaCodecInfo codec : decoderInfos) {
            if (codec.softwareOnly) {
                list.add(codec);
            }
        }
        return list;
    }
    public synchronized static List<MediaCodecInfo> getHardwareDecoders(String mimeType) {
        List<MediaCodecInfo> decoderInfos = getDecoderInfos(mimeType);
        List<MediaCodecInfo> list = new ArrayList<>();
        for (MediaCodecInfo codec : decoderInfos) {
            if (codec.hardwareAccelerated) {
                list.add(codec);
            }
        }
        return list;
    }
    @OptIn(markerClass = UnstableApi.class)
    public static MediaCodecInfo getLowLatencyDecoder(List<MediaCodecInfo> decoders) {
        for (MediaCodecInfo decoder : decoders) {
            if (decoder.name.contains("low_latency")) {
                return decoder;
            }
        }
        return null;
    }
}
