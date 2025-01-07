package com.pradeep.rtspplayer.utils;

import android.util.Log;
import android.util.Pair;

import androidx.media3.container.NalUnitUtil;
import androidx.media3.container.NalUnitUtil.SpsData;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class VideoCodecUtils {
    private static final String TAG = "VideoCodecUtils";
    public static final int MAX_NAL_SPS_SIZE = 500;
    public static final byte NAL_SLICE = 1;
    public static final byte NAL_DPA = 2;
    public static final byte NAL_DPB = 3;
    public static final byte NAL_DPC = 4;
    public static final byte NAL_IDR_SLICE = 5;
    public static final byte NAL_SEI = 6;
    public static final byte NAL_SPS = 7;
    public static final byte NAL_PPS = 8;
    public static final byte NAL_AUD = 9;
    public static final byte NAL_END_SEQUENCE = 10;
    public static final byte NAL_END_STREAM = 11;
    public static final byte NAL_FILLER_DATA = 12;
    public static final byte NAL_SPS_EXT = 13;
    public static final byte NAL_AUXILIARY_SLICE = 19;
    public static final byte NAL_STAP_A = 24;
    public static final byte NAL_STAP_B = 25;
    public static final byte NAL_MTAP16 = 26;
    public static final byte NAL_MTAP24 = 27;
    public static final byte NAL_FU_A = 28;
    public static final byte NAL_FU_B = 29;
    public static final byte H265_NAL_TRAIL_N = 0;
    public static final byte H265_NAL_TRAIL_R = 1;
    public static final byte H265_NAL_TSA_N = 2;
    public static final byte H265_NAL_TSA_R = 3;
    public static final byte H265_NAL_STSA_N = 4;
    public static final byte H265_NAL_STSA_R = 5;
    public static final byte H265_NAL_RADL_N = 6;
    public static final byte H265_NAL_RADL_R = 7;
    public static final byte H265_NAL_RASL_N = 8;
    public static final byte H265_NAL_RASL_R = 9;
    public static final byte H265_NAL_BLA_W_LP = 16;
    public static final byte H265_NAL_BLA_W_RADL = 17;
    public static final byte H265_NAL_BLA_N_LP = 18;
    public static final byte H265_NAL_IDR_W_RADL = 19;
    public static final byte H265_NAL_IDR_N_LP = 20;
    public static final byte H265_NAL_CRA_NUT = 21;
    public static final byte H265_NAL_VPS = 32;
    public static final byte H265_NAL_SPS = 33;
    public static final byte H265_NAL_PPS = 34;
    public static final byte H265_NAL_AUD = 35;
    public static final byte H265_NAL_EOS_NUT = 36;
    public static final byte H265_NAL_EOB_NUT = 37;
    public static final byte H265_NAL_FD_NUT = 38;
    public static final byte H265_NAL_SEI_PREFIX = 39;
    public static final byte H265_NAL_SEI_SUFFIX = 40;

    private static final byte[] NAL_PREFIX1 = new byte[]{0x00, 0x00, 0x00, 0x01};
    private static final byte[] NAL_PREFIX2 = new byte[]{0x00, 0x00, 0x01};

    public static int searchForNalUnitStart(
            byte[] data,
            int offset,
            int length,
            AtomicInteger prefixSize
    ) {
        if (offset >= data.length - 3) return -1;
        for (int pos = 0; pos < length; pos++) {
            int prefix = getNalUnitStartCodePrefixSize(data, pos + offset, length);
            if (prefix >= 0) {
                prefixSize.set(prefix);
                return pos + offset;
            }
        }
        return -1;
    }

    public static byte getNalUnitType(byte[] data, int offset, int length, boolean isH265) {
        if (data == null || length <= NAL_PREFIX1.length) return (byte) -1;
        int nalUnitTypeOctetOffset = -1;
        if (data[offset + NAL_PREFIX2.length - 1] == 1) {
            nalUnitTypeOctetOffset = offset + NAL_PREFIX2.length - 1;
        } else if (data[offset + NAL_PREFIX1.length - 1] == 1) {
            nalUnitTypeOctetOffset = offset + NAL_PREFIX1.length - 1;
        }

        if (nalUnitTypeOctetOffset != -1) {
            byte nalUnitTypeOctet = data[nalUnitTypeOctetOffset + 1];
            return (isH265)
                    ? (byte) ((nalUnitTypeOctet >> 1) & 0x3F)
                    : (byte) (nalUnitTypeOctet & 0x1F);
        } else {
            return (byte) -1;
        }
    }

    private static int getNalUnitStartCodePrefixSize(byte[] data, int offset, int length) {
        if (length < 4) return -1;
        return (memcmp(data, offset, NAL_PREFIX1, 0, NAL_PREFIX1.length))
                ? NAL_PREFIX1.length : (memcmp(data, offset, NAL_PREFIX2, 0, NAL_PREFIX2.length))
                ? NAL_PREFIX2.length : -1;
    }

    private static boolean memcmp(byte[] source1, int offsetSource1, byte[] source2, int offsetSource2, int num) {
        if (source1.length - offsetSource1 < num) return false;
        if (source2.length - offsetSource2 < num) return false;
        for (int i = 0; i < num; i++) {
            if (source1[offsetSource1 + i] != source2[offsetSource2 + i]) return false;
        }
        return true;
    }

    public static class NalUnit {
        public final byte type;
        public final int offset;
        public final int length;

        public NalUnit(byte type, int offset, int length) {
            this.type = type;
            this.offset = offset;
            this.length = length;
        }

        public byte getType() {
            return type;
        }

        public int getOffset() {
            return offset;
        }

        public int getLength() {
            return length;
        }
    }

    public static int getNalUnits(
            byte[] data,
            int dataOffset,
            int length,
            ArrayList<NalUnit> foundNals,
            boolean isH265
    ) {
        foundNals.clear();
        int nalUnits = 0;
        int nextNalOffset = 0;
        AtomicInteger nalUnitPrefixSize = new AtomicInteger(-1);
        long timestamp = System.currentTimeMillis();
        int offset = dataOffset;
        boolean stopped = false;
        while (!stopped) {
            int nalUnitIndex = searchForNalUnitStart(
                    data,
                    offset + nextNalOffset,
                    length - nextNalOffset,
                    nalUnitPrefixSize
            );
            if (nalUnitIndex >= 0) {
                nalUnits++;
                int nalUnitOffset = offset + nextNalOffset + nalUnitPrefixSize.get();
                byte nalUnitTypeOctet = data[nalUnitOffset];
                byte nalUnitType = (isH265)
                        ? (byte) ((nalUnitTypeOctet >> 1) & 0x3F)
                        : (byte) (nalUnitTypeOctet & 0x1F);
                int nextNalUnitStartIndex = searchForNalUnitStart(
                        data,
                        nalUnitOffset,
                        length - nalUnitOffset,
                        nalUnitPrefixSize
                );
                if (nextNalUnitStartIndex < 0) {
                    nextNalUnitStartIndex = length + dataOffset;
                    stopped = true;
                }
                int l = nextNalUnitStartIndex - offset;
                foundNals.add(new NalUnit(nalUnitType, offset, l));
                offset = nextNalUnitStartIndex;
                if (System.currentTimeMillis() - timestamp > 200) {
                    Log.w(TAG, "Cannot process data within 200 msec in " + length + " bytes (NALs found: " + foundNals.size() + ")");
                    break;
                }
            } else {
                stopped = true;
            }
        }
        return nalUnits;
    }

    public Pair<Integer, Integer> getNalUnitStartLengthFromArray(
            byte[] src, int offset, int length, boolean isH265, byte nalUnitType) {
        ArrayList<NalUnit> nalUnitsFound = new ArrayList<>();
        if (getNalUnits(src, offset, length, nalUnitsFound, isH265) > 0) {
            for (NalUnit nalUnit : nalUnitsFound) {
                if (nalUnit.getType() == nalUnitType) {
                    AtomicInteger prefixSize = new AtomicInteger();
                    int nalUnitIndex = searchForNalUnitStart(src, nalUnit.getOffset(), nalUnit.getLength(), prefixSize);
                    int nalOffset = nalUnitIndex + prefixSize.get() + 1; // NAL unit type
                    return new Pair<>(nalOffset, nalUnit.getLength());
                }
            }
        }
        return null;
    }

    @SuppressWarnings("UnsafeOptInUsageError")
    public SpsData getSpsNalUnitFromArray(byte[] src, int offset, int length, boolean isH265) {
        Pair<Integer, Integer> spsStartLength = getNalUnitStartLengthFromArray(src, offset, length, isH265, NAL_SPS);
        if (spsStartLength != null) {
            return NalUnitUtil.parseSpsNalUnitPayload(
                    src, spsStartLength.first, spsStartLength.first + spsStartLength.second);
        }
        return null;
    }

    @SuppressWarnings("UnsafeOptInUsageError")
    public Pair<Integer, Integer> getWidthHeightFromArray(byte[] src, int offset, int length, boolean isH265) {
        SpsData sps = getSpsNalUnitFromArray(src, offset, length, isH265);
        if (sps != null) {
            return new Pair<>(sps.width, sps.height);
        }
        return null;
    }

    public boolean isAnyKeyFrame(byte[] data, int offset, int length, boolean isH265) {
        if (data == null || length <= 0) return false;
        int currOffset = offset;

        AtomicInteger nalUnitPrefixSize = new AtomicInteger(-1);
        long timestamp = System.currentTimeMillis();
        while (true) {
            int nalUnitIndex = searchForNalUnitStart(data, currOffset, length, nalUnitPrefixSize);

            if (nalUnitIndex >= 0) {
                int nalUnitOffset = nalUnitIndex + nalUnitPrefixSize.get();
                if (nalUnitOffset >= data.length)
                    return false;
                byte nalUnitTypeOctet = data[nalUnitOffset];

                if (isH265) {
                    byte nalUnitType = (byte) ((nalUnitTypeOctet & 0x7E) >> 1);
                    // Treat SEI_PREFIX as key frame.
                    if (nalUnitType == H265_NAL_IDR_W_RADL || nalUnitType == H265_NAL_IDR_N_LP)
                        return true;
                } else {
                    byte nalUnitType = (byte) (nalUnitTypeOctet & 0x1F);
                    if (nalUnitType == NAL_IDR_SLICE)
                        return true;
                    else if (nalUnitType == NAL_SLICE)
                        return false;
                }
                currOffset = nalUnitOffset;
                if (System.currentTimeMillis() - timestamp > 100) {
                    Log.w(TAG, "Cannot process data within 100 msec in " + length + " bytes (index=" + nalUnitIndex + ")");
                    break;
                }
            } else {
                break;
            }
        }

        return false;
    }

    public String getH264NalUnitTypeString(byte nalUnitType) {
        switch (nalUnitType) {
            case NAL_SLICE:
                return "NAL_SLICE";
            case NAL_DPA:
                return "NAL_DPA";
            case NAL_DPB:
                return "NAL_DPB";
            case NAL_DPC:
                return "NAL_DPC";
            case NAL_IDR_SLICE:
                return "NAL_IDR_SLICE";
            case NAL_SEI:
                return "NAL_SEI";
            case NAL_SPS:
                return "NAL_SPS";
            case NAL_PPS:
                return "NAL_PPS";
            case NAL_AUD:
                return "NAL_AUD";
            case NAL_END_SEQUENCE:
                return "NAL_END_SEQUENCE";
            case NAL_END_STREAM:
                return "NAL_END_STREAM";
            case NAL_FILLER_DATA:
                return "NAL_FILLER_DATA";
            case NAL_SPS_EXT:
                return "NAL_SPS_EXT";
            case NAL_AUXILIARY_SLICE:
                return "NAL_AUXILIARY_SLICE";
            case NAL_STAP_A:
                return "NAL_STAP_A";
            case NAL_STAP_B:
                return "NAL_STAP_B";
            case NAL_MTAP16:
                return "NAL_MTAP16";
            case NAL_MTAP24:
                return "NAL_MTAP24";
            case NAL_FU_A:
                return "NAL_FU_A";
            case NAL_FU_B:
                return "NAL_FU_B";
            default:
                return "unknown - " + nalUnitType;
        }
    }

    public String getH265NalUnitTypeString(byte nalUnitType) {
        switch (nalUnitType) {
            case H265_NAL_TRAIL_N:
                return "NAL_TRAIL_N";
            case H265_NAL_TRAIL_R:
                return "NAL_TRAIL_R";
            case H265_NAL_TSA_N:
                return "NAL_TSA_N";
            case H265_NAL_TSA_R:
                return "NAL_TSA_R";
            case H265_NAL_STSA_N:
                return "NAL_STSA_N";
            case H265_NAL_STSA_R:
                return "NAL_STSA_R";
            case H265_NAL_RADL_N:
                return "NAL_RADL_N";
            case H265_NAL_RADL_R:
                return "NAL_RADL_R";
            case H265_NAL_RASL_N:
                return "NAL_RASL_N";
            case H265_NAL_RASL_R:
                return "NAL_RASL_R";
            case H265_NAL_BLA_W_LP:
                return "NAL_BLA_W_LP";
            case H265_NAL_BLA_W_RADL:
                return "NAL_BLA_W_RADL";
            case H265_NAL_BLA_N_LP:
                return "NAL_BLA_N_LP";
            case H265_NAL_IDR_W_RADL:
                return "NAL_IDR_W_RADL";
            case H265_NAL_IDR_N_LP:
                return "NAL_IDR_N_LP";
            case H265_NAL_CRA_NUT:
                return "NAL_CRA_NUT";
            case H265_NAL_VPS:
                return "NAL_VPS";
            case H265_NAL_SPS:
                return "NAL_SPS";
            case H265_NAL_PPS:
                return "NAL_PPS";
            case H265_NAL_AUD:
                return "NAL_AUD";
            case H265_NAL_EOS_NUT:
                return "NAL_EOS_NUT";
            case H265_NAL_EOB_NUT:
                return "NAL_EOB_NUT";
            case H265_NAL_FD_NUT:
                return "NAL_FD_NUT";
            case H265_NAL_SEI_PREFIX:
                return "NAL_SEI_PREFIX";
            case H265_NAL_SEI_SUFFIX:
                return "NAL_SEI_SUFFIX";
            default:
                return "unknown - " + nalUnitType;
        }
    }
}
