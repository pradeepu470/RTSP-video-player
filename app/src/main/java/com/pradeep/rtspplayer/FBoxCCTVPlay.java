package com.pradeep.rtspplayer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.media.MediaSync;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class FBoxCCTVPlay extends Thread {
    private final String TAG = "FBoxCCTVPlay";
    private int height;
    public boolean mStopRead = false;
    private MediaCodec mediaCodec;
    private String mimeType;
    private Surface surface;
    private int width;
    public ArrayList<DecoderData> mAccessUnits = new ArrayList<>();
    public ArrayList<Integer> mAvailableDecoderInputBuffer = new ArrayList<>();
    private int captureRate = 1;

    public Handler mHandler = null;
    private void createHandler() {
        mHandler = new Handler() {
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                message what = message.values()[msg.what];
                switch (what) {
                    case kWhatInputBufferAvailable: {
                        inputBufferProcess(msg);
                        break;
                    }
                    case kWhatOutputBufferAvailable: {
                        outputBufferProcess(msg);
                        break;
                    }
                    default:
                        Log.e("VideoDecodeThread", "No case match");
                        break;

                }
            }
        };
    }

    public enum message {
        kWhatInputBufferAvailable,
        kWhatOutputBufferAvailable
    }

    public class DecoderData {
        private byte [] mData;

        public DecoderData(byte [] data) {
            this.mData = data;
        }

        public byte[] getData() {
            return mData;
        }
    }


    public void putData(final byte[] data,int size){
        if(data != null) {
            DecoderData frame = new DecoderData(data);
            mAccessUnits.add(frame);
        }
    }

    public void run() {
        Log.e("VideoDecodeThread", "FBox Dvb Player Thread Started");
        new Thread() {
            public void run() {
                super.run();
                createCodec();
            }
        }.start();
    }


    public void inputBufferProcess(Message msg) {
        int index = msg.arg1;
        MediaCodec mediaCodec2 = (MediaCodec) msg.obj;
        if (!this.mAccessUnits.isEmpty()) {
            try {
                if(mediaCodec2 != null) {
                    ByteBuffer byteBuffer = mediaCodec2.getInputBuffer(index);
                    if (byteBuffer != null) {
                        byteBuffer.clear();
                    }
                    if ((!this.mAccessUnits.isEmpty())) {
                        DecoderData frame = this.mAccessUnits.get(0);
                        if (frame != null) {
                            byte[] playData = frame.getData();
                            byteBuffer.put(playData, 0, playData.length);
                            mediaCodec2.queueInputBuffer(index, 0, playData.length, 0, 0);
                            this.mAccessUnits.remove(0);
                            if (mAvailableDecoderInputBuffer.size() > 0) {
                                this.mAvailableDecoderInputBuffer.remove(0);
                            }
                        }
                    } else {
                        Log.v("ThreadForCCTVRecord", "no buffer available...");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            while(this.mAccessUnits.size() > 0) {
                try {
                    sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            Message timeOut = new Message();
            timeOut.what = message.kWhatInputBufferAvailable.ordinal();
            timeOut.arg1 = index;
            timeOut.obj = mediaCodec2;
            if (this.mHandler != null && this.mHandler.sendMessageDelayed(timeOut, 2000)) {

            }
        }
    }

    private void outputBufferProcess(Message msg) {
        try {
            ((MediaCodec) msg.obj).releaseOutputBuffer(msg.arg1, true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public final class FBoxDecoderCallback extends MediaCodec.Callback {
        private FBoxDecoderCallback() {
        }

        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int index) {
            if (mAvailableDecoderInputBuffer != null) {
                mAvailableDecoderInputBuffer.add(Integer.valueOf(index));
            }
            if (!mAvailableDecoderInputBuffer.isEmpty() || !mStopRead) {
                Message timeOut = new Message();
                timeOut.what = message.kWhatInputBufferAvailable.ordinal();
                timeOut.arg1 = index;
                timeOut.obj = mediaCodec;
                if (mHandler != null && mHandler.sendMessageDelayed(timeOut, 50)) {
                    return;
                }
                return;
            }
            mAvailableDecoderInputBuffer.remove(0);
        }

        public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (!mStopRead) {
                mediaCodec.getOutputBuffer(index);
                mediaCodec.getOutputFormat(index);
                try {
                    mediaCodec.releaseOutputBuffer(index, info.presentationTimeUs);

                    try {
                        Thread.sleep((int)1000/captureRate);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
            Log.e("VideoDecodeThread", "Mediacodec reported error. isRecoverable  : " + e.isRecoverable());
        }

        public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
            Log.d("VideoDecodeThread", "Output Format changed : " + mediaFormat.toString());
        }
    }

    public boolean isCodecSupported(String mimeType) {
        for (MediaCodecInfo codecInfo : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (codecInfo.isEncoder()) continue;
            for (String type : codecInfo.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mimeType)) {
                    return true;
                }
            }
        }
        return false;
    }
    private MediaCodec findHevcDecoder() {
        final String[] codecNames = {"video/hevc", "OMX.google.hevc.decoder"};
        for (String name : codecNames) {
            try {
                MediaCodec codec = MediaCodec.createByCodecName(name);
                Log.i(TAG, "codec \"" + name + "\" is available");
                return codec;
            } catch (IOException | IllegalArgumentException ex) {
                Log.d(TAG, "codec \"" + name + "\" not found");
            }
        }
        Log.w(TAG, "HEVC decoder is not available");
        return null;
    }

    private void createCodec() {
        Log.e("VideoDecodeThread", "FBox Dvb Player create codec"+isCodecSupported(this.mimeType));
        try {
            if(this.mimeType.equalsIgnoreCase("video/hevc")) {
                this.mediaCodec = findHevcDecoder();
            } else {
                this.mediaCodec = MediaCodec.createDecoderByType(this.mimeType);
            }
            this.mediaCodec.setCallback(new FBoxDecoderCallback());
        } catch (Exception e) {
            e.printStackTrace();
        }
        MediaSync sync = new MediaSync();
        sync.setSurface(surface);
        Surface inputSurface = sync.createInputSurface();
        sync.setPlaybackParams(new PlaybackParams().setSpeed(0.5f));
        MediaFormat format = MediaFormat.createVideoFormat(this.mimeType, this.width, this.height);
        format.setInteger("max-input-size", 1958400);
        format.setInteger("bitrate", 4096);
        format.setInteger("frame-rate", 25);
        format.setInteger("i-frame-interval", 30);
        this.mediaCodec.configure(format, inputSurface, (MediaCrypto) null, 0);
        this.mediaCodec.start();
        captureRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
        while (this.mAccessUnits.size() < 10) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e2) {
                e2.printStackTrace();
            }
        }
    }

    public void stopPlayer() {
        this.mStopRead = true;
        try {
            if(this.mHandler != null) {
                this.mHandler.removeMessages(message.kWhatInputBufferAvailable.ordinal());
                this.mHandler.removeMessages(message.kWhatOutputBufferAvailable.ordinal());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.mAvailableDecoderInputBuffer.clear();
        this.mAccessUnits.clear();
        if(this.mediaCodec != null) {
            this.mediaCodec.stop();
            this.mediaCodec.release();
            Log.d("VideoDecodeThread", "VideoDecodeThread stopped");
            this.mediaCodec = null;
        }
        this.mHandler = null;
    }

    public FBoxCCTVPlay(Surface surface2, String mimeType2, int width2, int height2) {
        this.surface = surface2;
        this.mimeType = mimeType2;
        this.width = width2;
        this.height = height2;
        createHandler();
    }
}
