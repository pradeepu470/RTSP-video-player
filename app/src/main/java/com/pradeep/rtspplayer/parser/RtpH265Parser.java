package com.pradeep.rtspplayer.parser;

public class RtpH265Parser extends RtpParser {

    @Override
    public byte[] processRtpPacketAndGetNalUnit(byte[] data, int length, boolean marker) {
        byte nalType = (byte) ((data[0] >> 1) & 0x3F);
        byte[] nalUnit = null;
        if (nalType < RTP_PACKET_TYPE_AP) {
            nalUnit = processSingleFramePacket(data, length);
            clearFragmentedBuffer();
        } else if (nalType == RTP_PACKET_TYPE_FU) {
            nalUnit = processFragmentationUnitPacket(data, length, marker);
        }

        return nalUnit;
    }

    private byte[] processFragmentationUnitPacket(byte[] data, int length, boolean marker) {
        int fuHeader = data[2] & 0xFF;
        boolean isFirstFuPacket = (fuHeader & 0x80) > 0;
        boolean isLastFuPacket = (fuHeader & 0x40) > 0;

        if (isFirstFuPacket) {
            addStartFragmentedPacket(data, length);
        } else if (isLastFuPacket || marker) {
            return addEndFragmentedPacketAndCombine(data, length);
        } else {
            addMiddleFragmentedPacket(data, length);
        }
        return null;
    }

    private void addStartFragmentedPacket(byte[] data, int length) {
        fragmentedPackets = 0;
        fragmentedBufferLength = length - 1;
        fragmentedBuffer[0] = new byte[fragmentedBufferLength];

        int tid = data[1] & 0x7;
        int fuHeader = data[2] & 0xFF;
        int nalUnitType = fuHeader & 0x3F;

        fragmentedBuffer[0][0] = (byte) (((nalUnitType << 1) & 0x7F));
        fragmentedBuffer[0][1] = (byte) tid;
        System.arraycopy(data, 3, fragmentedBuffer[0], 2, length - 3);
    }

    private void addMiddleFragmentedPacket(byte[] data, int length) {
        fragmentedPackets++;
        if (fragmentedPackets >= fragmentedBuffer.length) {
            fragmentedBuffer[0] = null;
        } else {
            fragmentedBufferLength += length - 3;
            fragmentedBuffer[fragmentedPackets] = new byte[length - 3];
            System.arraycopy(data, 3, fragmentedBuffer[fragmentedPackets], 0, length - 3);
        }
    }

    private byte[] addEndFragmentedPacketAndCombine(byte[] data, int length) {
        byte[] nalUnit = null;
        if (fragmentedBuffer[0] != null) {
            nalUnit = new byte[fragmentedBufferLength + length + 3];
            writeNalPrefix0001(nalUnit);
            int tmpLen = 4;
            for (int i = 0; i <= fragmentedPackets; i++) {
                System.arraycopy(fragmentedBuffer[i], 0, nalUnit, tmpLen, fragmentedBuffer[i].length);
                tmpLen += fragmentedBuffer[i].length;
            }
            System.arraycopy(data, 3, nalUnit, tmpLen, length - 3);
            clearFragmentedBuffer();
        }
        return nalUnit;
    }

    private void clearFragmentedBuffer() {
        for (int i = 0; i <= fragmentedPackets; i++) {
            fragmentedBuffer[i] = null;
        }
    }
    private static final byte RTP_PACKET_TYPE_AP = 48;
    private static final byte RTP_PACKET_TYPE_FU = 49;
}
