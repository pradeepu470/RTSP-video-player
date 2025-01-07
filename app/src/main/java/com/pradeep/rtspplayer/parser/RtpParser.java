package com.pradeep.rtspplayer.parser;

public abstract class RtpParser {

    public abstract byte[] processRtpPacketAndGetNalUnit(byte[] data, int length, boolean marker);
    protected byte[][] fragmentedBuffer = new byte[1024][];
    protected int fragmentedBufferLength = 0;
    protected int fragmentedPackets = 0;

    protected void writeNalPrefix0001(byte[] buffer) {
        buffer[0] = (byte) 0x00;
        buffer[1] = (byte) 0x00;
        buffer[2] = (byte) 0x00;
        buffer[3] = (byte) 0x01;
    }

    protected byte[] processSingleFramePacket(byte[] data, int length) {
        byte[] result = new byte[4 + length];
        writeNalPrefix0001(result);
        System.arraycopy(data, 0, result, 4, length);
        return result;
    }
}

