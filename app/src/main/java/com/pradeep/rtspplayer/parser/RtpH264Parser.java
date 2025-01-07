package com.pradeep.rtspplayer.parser;


import com.pradeep.rtspplayer.utils.VideoCodecUtils;

public class RtpH264Parser extends RtpParser {

    @Override
    public byte[] processRtpPacketAndGetNalUnit(byte[] data, int length, boolean marker) {
        byte nalType = (byte) (data[0] & 0x1F);
        int packFlag = data[1] & 0xC0;
        byte[] nalUnit = null;
        switch (nalType) {
            case VideoCodecUtils.NAL_STAP_A:
            case VideoCodecUtils.NAL_STAP_B:
            case VideoCodecUtils.NAL_MTAP16:
            case VideoCodecUtils.NAL_MTAP24:
                break;
            case VideoCodecUtils.NAL_FU_A:
                switch (packFlag) {
                    case 0x80:
                        addStartFragmentedPacket(data, length);
                        break;
                    case 0x00:
                        if (marker) {
                            nalUnit = addEndFragmentedPacketAndCombine(data, length);
                        } else {
                            addMiddleFragmentedPacket(data, length);
                        }
                        break;
                    case 0x40:
                        nalUnit = addEndFragmentedPacketAndCombine(data, length);
                        break;
                }
                break;

            case VideoCodecUtils.NAL_FU_B:
                break;

            default:
                nalUnit = processSingleFramePacket(data, length);
                clearFragmentedBuffer();
                break;
        }
        return nalUnit;
    }

    private void addStartFragmentedPacket(byte[] data, int length) {
        fragmentedPackets = 0;
        fragmentedBufferLength = length - 1;
        fragmentedBuffer[0] = new byte[fragmentedBufferLength];
        fragmentedBuffer[0][0] = (byte) ((data[0] & 0xE0) | (data[1] & 0x1F));
        System.arraycopy(data, 2, fragmentedBuffer[0], 1, length - 2);
    }

    private void addMiddleFragmentedPacket(byte[] data, int length) {
        fragmentedPackets++;
        if (fragmentedPackets >= fragmentedBuffer.length) {
            fragmentedBuffer[0] = null;
        } else {
            fragmentedBufferLength += length - 2;
            fragmentedBuffer[fragmentedPackets] = new byte[length - 2];
            System.arraycopy(data, 2, fragmentedBuffer[fragmentedPackets], 0, length - 2);
        }
    }

    private byte[] addEndFragmentedPacketAndCombine(byte[] data, int length) {
        byte[] nalUnit = null;
        int tmpLen;
        if (fragmentedBuffer[0] != null) {
            nalUnit = new byte[fragmentedBufferLength + length + 2];
            writeNalPrefix0001(nalUnit);
            tmpLen = 4;
            for (int i = 0; i <= fragmentedPackets; i++) {
                System.arraycopy(fragmentedBuffer[i], 0, nalUnit, tmpLen, fragmentedBuffer[i].length);
                tmpLen += fragmentedBuffer[i].length;
            }
            System.arraycopy(data, 2, nalUnit, tmpLen, length - 2);
            clearFragmentedBuffer();
        }
        return nalUnit;
    }

    private void clearFragmentedBuffer() {
        for (int i = 0; i <= fragmentedPackets; i++) {
            fragmentedBuffer[i] = null;
        }
    }
}

