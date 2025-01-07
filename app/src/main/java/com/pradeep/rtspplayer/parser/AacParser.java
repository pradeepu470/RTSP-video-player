package com.pradeep.rtspplayer.parser;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;

@SuppressLint("UnsafeOptInUsageError")
public class AacParser {
    private final ParsableBitArray headerScratchBits;
    private final ParsableByteArray headerScratchBytes;

    private static final int MODE_LBR = 0;
    private static final int MODE_HBR = 1;

    private static final int[] NUM_BITS_AU_SIZES = {6, 13};

    private static final int[] NUM_BITS_AU_INDEX = {2, 3};

    private final int _aacMode;
    private boolean completeFrameIndicator = true;

    public AacParser(@NonNull String aacMode) {
        _aacMode = aacMode.equalsIgnoreCase("AAC-lbr") ? MODE_LBR : MODE_HBR;
        headerScratchBits = new ParsableBitArray();
        headerScratchBytes = new ParsableByteArray();
    }

    @Nullable
    public byte[] processRtpPacketAndGetSample(@NonNull byte[] data, int length) {
        int auHeadersCount = 1;
        int numBitsAuSize = NUM_BITS_AU_SIZES[_aacMode];
        int numBitsAuIndex = NUM_BITS_AU_INDEX[_aacMode];
        ParsableByteArray packet = new ParsableByteArray(data, length);
        int auHeadersLength = packet.readShort();//((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        int auHeadersLengthBytes = (auHeadersLength + 7) / 8;

        headerScratchBytes.reset(auHeadersLengthBytes);
        packet.readBytes(headerScratchBytes.getData(), 0, auHeadersLengthBytes);
        headerScratchBits.reset(headerScratchBytes.getData());

        int bitsAvailable = auHeadersLength - (numBitsAuSize + numBitsAuIndex);

        if (bitsAvailable > 0) {// && (numBitsAuSize + numBitsAuSize) > 0) {
            auHeadersCount += bitsAvailable / (numBitsAuSize + numBitsAuIndex);
        }

        if (auHeadersCount == 1) {
            int auSize = headerScratchBits.readBits(numBitsAuSize);
            int auIndex = headerScratchBits.readBits(numBitsAuIndex);

            if (completeFrameIndicator) {
                if (auIndex == 0) {
                    if (packet.bytesLeft() == auSize) {
                        return handleSingleAacFrame(packet);

                    }
                }
            }

        }
        return new byte[0];
    }

    private byte[] handleSingleAacFrame(ParsableByteArray packet) {
        int length = packet.bytesLeft();
        byte[] data = new byte[length];
        System.arraycopy(packet.getData(), packet.getPosition(), data, 0, data.length);
        return data;
    }

}
