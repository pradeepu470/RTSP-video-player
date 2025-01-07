package com.pradeep.rtspplayer;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pradeep.rtspplayer.parser.AacParser;
import com.pradeep.rtspplayer.parser.RtpH264Parser;
import com.pradeep.rtspplayer.parser.RtpH265Parser;
import com.pradeep.rtspplayer.parser.RtpHeaderParser;
import com.pradeep.rtspplayer.parser.RtpParser;
import com.pradeep.rtspplayer.utils.NetUtils;
import com.pradeep.rtspplayer.utils.VideoCodecUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RtspClient {

    private static final String TAG = "RtspClient";
    static final String TAG_DEBUG = TAG + " DBG";
    private static final boolean DEBUG = true;
    private static final byte[] EMPTY_ARRAY = new byte[0];

    public final static int RTSP_CAPABILITY_NONE          = 0;
    public final static int RTSP_CAPABILITY_OPTIONS       = 1 << 1;
    public final static int RTSP_CAPABILITY_DESCRIBE      = 1 << 2;
    public final static int RTSP_CAPABILITY_ANNOUNCE      = 1 << 3;
    public final static int RTSP_CAPABILITY_SETUP         = 1 << 4;
    public final static int RTSP_CAPABILITY_PLAY          = 1 << 5;
    public final static int RTSP_CAPABILITY_RECORD        = 1 << 6;
    public final static int RTSP_CAPABILITY_PAUSE         = 1 << 7;
    public final static int RTSP_CAPABILITY_TEARDOWN      = 1 << 8;
    public final static int RTSP_CAPABILITY_SET_PARAMETER = 1 << 9;
    public final static int RTSP_CAPABILITY_GET_PARAMETER = 1 << 10;
    public final static int RTSP_CAPABILITY_REDIRECT      = 1 << 11;

    public static boolean hasCapability(int capability, int capabilitiesMask) {
        return (capabilitiesMask & capability) != 0;
    }

    public interface RtspClientListener {
        void onRtspConnecting();
        void onRtspConnected(@NonNull SdpInfo sdpInfo);
        void onRtspVideoNalUnitReceived(@NonNull byte[] data, int offset, int length, long timestamp);
        void onRtspAudioSampleReceived(@NonNull byte[] data, int offset, int length, long timestamp);
        void onRtspApplicationDataReceived(@NonNull byte[] data, int offset, int length, long timestamp);
        void onRtspDisconnecting();
        void onRtspDisconnected();
        void onRtspFailedUnauthorized();
        void onRtspFailed(@Nullable String message);
    }

    private interface RtspClientKeepAliveListener {
        void onRtspKeepAliveRequested();
    }

    public static class SdpInfo {
        public @Nullable String sessionName;
        public @Nullable String sessionDescription;
        public @Nullable VideoTrack videoTrack;
        public @Nullable AudioTrack audioTrack;
        public @Nullable ApplicationTrack applicationTrack;
    }

    public abstract static class Track {
        public String request;
        public int payloadType;

        @NonNull
        @Override
        public String toString() {
            return "Track{request='" + request + "', payloadType=" + payloadType + '}';
        }
    }

    public static final int VIDEO_CODEC_H264 = 0;
    public static final int VIDEO_CODEC_H265 = 1;

    public static class VideoTrack extends Track {
        public int videoCodec = VIDEO_CODEC_H264;
        public @Nullable byte[] sps; // Both H.264 and H.265
        public @Nullable byte[] pps; // Both H.264 and H.265
        public @Nullable byte[] vps; // H.265 only

        @Override
        public String toString() {
            return "VideoTrack{" +
                    "videoCodec=" + videoCodec +
                    ", sps=" + Arrays.toString(sps) +
                    ", pps=" + Arrays.toString(pps) +
                    ", vps=" + Arrays.toString(vps) +
                    '}';
        }
    }

    public static final int AUDIO_CODEC_UNKNOWN = -1;
    public static final int AUDIO_CODEC_AAC = 0;
    public static final int AUDIO_CODEC_OPUS = 1;

    public static class AudioTrack extends Track {
        public int audioCodec = AUDIO_CODEC_UNKNOWN;
        public int sampleRateHz; // 16000, 8000
        public int channels; // 1 - mono, 2 - stereo
        public String mode; // AAC-lbr, AAC-hbr
        public @Nullable byte[] config; // config=1210fff15081ffdffc
    }

    public static class ApplicationTrack extends Track {
    }

    private static final String CRLF = "\r\n";
    private final static int MAX_LINE_SIZE = 4098;

    private static class UnauthorizedException extends IOException {
        UnauthorizedException() {
            super("Unauthorized");
        }
    }

    private final static class NoResponseHeadersException extends IOException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private final @NonNull Socket rtspSocket;
    private @NonNull String uriRtsp;
    private final @NonNull RtspClientListener listener;
    private final boolean requestVideo;
    private final boolean requestAudio;
    private final boolean requestApplication;
    private final boolean debug;
    private final @Nullable String username;
    private final @Nullable String password;
    private final @Nullable String userAgent;

    private static boolean mCloseConnect = false;

    private RtspClient(@NonNull Builder builder) {
        rtspSocket = builder.rtspSocket;
        uriRtsp = builder.uriRtsp;
        listener = builder.listener;
        requestVideo = builder.requestVideo;
        requestAudio = builder.requestAudio;
        requestApplication = builder.requestApplication;
        username = builder.username;
        password = builder.password;
        debug = builder.debug;
        userAgent = builder.userAgent;
        mCloseConnect = false;
    }

    public void execute() {
        Log.v(TAG, "execute()");
        listener.onRtspConnecting();
        Log.v(TAG, "1execute()");
        try {
            final InputStream inputStream = rtspSocket.getInputStream();
            final OutputStream outputStream = debug ? new LoggerOutputStream(rtspSocket.getOutputStream()) : new BufferedOutputStream(rtspSocket.getOutputStream());

            Log.v(TAG, "2execute()");
            SdpInfo sdpInfo = new SdpInfo();
            ArrayList<Pair<String, String>> headers;
            int status;

            Log.v(TAG, "3execute()");
            String authToken = null;
            Pair<String, String> digestRealmNonce = null;
            Log.v(TAG, "5execute()");
            sendOptionsCommand(outputStream, uriRtsp, 0, userAgent, null);
            status = readResponseStatusCode(inputStream);
            headers = readResponseHeaders(inputStream);
            dumpHeaders(headers);
            Log.v(TAG, "execute()"+status);
            if (status == 401) {
                digestRealmNonce = getHeaderWwwAuthenticateDigestRealmAndNonce(headers);
                if (digestRealmNonce == null) {
                    String basicRealm = getHeaderWwwAuthenticateBasicRealm(headers);
                    if (TextUtils.isEmpty(basicRealm)) {
                        throw new IOException("Unknown authentication type");
                    }
                    authToken = getBasicAuthHeader(username, password);
                } else {
                    // Digest auth
                    authToken = getDigestAuthHeader(username, password, "OPTIONS", uriRtsp, digestRealmNonce.first, digestRealmNonce.second);
                }
                sendOptionsCommand(outputStream, uriRtsp, 1, userAgent, authToken);
                status = readResponseStatusCode(inputStream);
                headers = readResponseHeaders(inputStream);
                dumpHeaders(headers);
            }
            Log.i(TAG, "OPTIONS status: " + status);
            checkStatusCode(status);
            final int capabilities = getSupportedCapabilities(headers);
            sendDescribeCommand(outputStream, uriRtsp, 2, userAgent, authToken);
            status = readResponseStatusCode(inputStream);
            headers = readResponseHeaders(inputStream);
            dumpHeaders(headers);
            if (status == 401) {
                digestRealmNonce = getHeaderWwwAuthenticateDigestRealmAndNonce(headers);
                if (digestRealmNonce == null) {
                    String basicRealm = getHeaderWwwAuthenticateBasicRealm(headers);
                    if (TextUtils.isEmpty(basicRealm)) {
                        throw new IOException("Unknown authentication type");
                    }
                    // Basic auth
                    authToken = getBasicAuthHeader(username, password);
                } else {
                    // Digest auth
                    authToken = getDigestAuthHeader(username, password, "DESCRIBE", uriRtsp, digestRealmNonce.first, digestRealmNonce.second);
                }
                //(exitFlag);
                sendDescribeCommand(outputStream, uriRtsp, 3, userAgent, authToken);
                status = readResponseStatusCode(inputStream);
                headers = readResponseHeaders(inputStream);
                dumpHeaders(headers);
            }
            checkStatusCode(status);
            String contentBaseUri = getHeaderContentBase(headers);
            if (contentBaseUri != null) {
                uriRtsp = contentBaseUri;
            }
            int contentLength = getHeaderContentLength(headers);
            if (contentLength > 0) {
                String content = readContentAsText(inputStream, contentLength);
                if (debug)
                    Log.i(TAG_DEBUG, "" + content);
                try {
                    List<Pair<String, String>> params = getDescribeParams(content);
                    sdpInfo = getSdpInfoFromDescribeParams(params);
                    if (!requestVideo)
                        sdpInfo.videoTrack = null;
                    if (!requestAudio)
                        sdpInfo.audioTrack = null;
                    if (!requestApplication)
                        sdpInfo.applicationTrack = null;
                    if (sdpInfo.audioTrack != null && sdpInfo.audioTrack.audioCodec == AUDIO_CODEC_UNKNOWN) {
                        Log.e(TAG_DEBUG, "Unknown RTSP audio codec (" + sdpInfo.audioTrack.audioCodec + ") specified in SDP");
                        sdpInfo.audioTrack = null;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            String session = null;
            int sessionTimeout = 0;
            for (int i = 0; i < 3; i++) {
                //(exitFlag);
                Track track;
                switch (i) {
                    case 0 -> track = requestVideo ? sdpInfo.videoTrack : null;
                    case 1 -> track = requestAudio ? sdpInfo.audioTrack : null;
                    default -> track = requestApplication ? sdpInfo.applicationTrack : null;
                }
                if (track != null) {
                    String uriRtspSetup = getUriForSetup(uriRtsp, track);
                    if (uriRtspSetup == null) {
                        Log.e(TAG, "Failed to get RTSP URI for SETUP");
                        continue;
                    }
                    if (digestRealmNonce != null)
                        authToken = getDigestAuthHeader(
                                username,
                                password,
                                "SETUP",
                                uriRtspSetup,
                                digestRealmNonce.first,
                                digestRealmNonce.second);
                    sendSetupCommand(
                            outputStream,
                            uriRtspSetup,
                            4,
                            userAgent,
                            authToken,
                            session,
                            (i == 0 ? "0-1" /*video*/ : "2-3" /*audio*/));
                    status = readResponseStatusCode(inputStream);
                    checkStatusCode(status);
                    headers = readResponseHeaders(inputStream);
                    dumpHeaders(headers);
                    session = getHeader(headers, "Session");
                    if (!TextUtils.isEmpty(session)) {
                        String[] params = TextUtils.split(session, ";");
                        session = params[0];
                        if (params.length > 1) {
                            params = TextUtils.split(params[1], "=");
                            if (params.length > 1) {
                                try {
                                    sessionTimeout = Integer.parseInt(params[1]);
                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "Failed to parse RTSP session timeout");
                                }
                            }
                        }
                    }
                    if (TextUtils.isEmpty(session))
                        throw new IOException("Failed to get RTSP session");
                }
            }

            if (TextUtils.isEmpty(session))
                throw new IOException("Failed to get any media track");
            //(exitFlag);
            if (digestRealmNonce != null)
                authToken = getDigestAuthHeader(username, password, "PLAY", uriRtsp /*?*/, digestRealmNonce.first, digestRealmNonce.second);
            sendPlayCommand(outputStream, uriRtsp, 5, userAgent, authToken, session);
            status = readResponseStatusCode(inputStream);
            Log.i(TAG, "PLAY status: " + status);
            checkStatusCode(status);
            headers = readResponseHeaders(inputStream);
            dumpHeaders(headers);

            listener.onRtspConnected(sdpInfo);

            if (sdpInfo.videoTrack != null ||  sdpInfo.audioTrack != null || sdpInfo.applicationTrack != null) {
                if (digestRealmNonce != null)
                    authToken = getDigestAuthHeader(username, password, hasCapability(RTSP_CAPABILITY_GET_PARAMETER, capabilities) ? "GET_PARAMETER" : "OPTIONS", uriRtsp, digestRealmNonce.first, digestRealmNonce.second);
                final String authTokenFinal = authToken;
                final String sessionFinal = session;
                RtspClientKeepAliveListener keepAliveListener = () -> {
                    try {

                        Log.e(TAG,"this.................get paramater");
                        if (hasCapability(RTSP_CAPABILITY_GET_PARAMETER, capabilities))
                            sendGetParameterCommand(outputStream, uriRtsp, 6, userAgent, sessionFinal, authTokenFinal);
                        else
                            sendOptionsCommand(outputStream, uriRtsp, 7, userAgent, authTokenFinal);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                };
                try {

                    Log.e(TAG,"this.................");
                    readRtpData(
                            inputStream,
                            sdpInfo,
                            listener,
                            sessionTimeout / 2 * 1000,
                            keepAliveListener);
                } finally {
                    if (hasCapability(RTSP_CAPABILITY_TEARDOWN, capabilities)) {
                        if (digestRealmNonce != null)
                            authToken = getDigestAuthHeader(username, password, "TEARDOWN", uriRtsp, digestRealmNonce.first, digestRealmNonce.second);
                        sendTeardownCommand(outputStream, uriRtsp, 7, userAgent, authToken, sessionFinal);
                    }
                }

            } else {
                listener.onRtspFailed("No tracks found. RTSP server issue.");
            }

            listener.onRtspDisconnecting();
            listener.onRtspDisconnected();
        } catch (UnauthorizedException e) {
            e.printStackTrace();
            listener.onRtspFailedUnauthorized();
        } catch (Exception e) {
            e.printStackTrace();
            listener.onRtspFailed(e.getMessage());
        }
        try {
            rtspSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void closeSocketConnect() {
        mCloseConnect = true;
        try {
            rtspSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Nullable
    private static String getUriForSetup(@NonNull String uriRtsp, @Nullable Track track) {
        if (track == null)
            return null;
        if (track.request == null) {
            Log.w(TAG, "Track request is empty. Skipping it.");
            track.request = uriRtsp;
        }
        String uriRtspSetup = uriRtsp;
        if (track.request.startsWith("rtsp://") || track.request.startsWith("rtsps://")) {
            uriRtspSetup = track.request;
        } else {
            if (!track.request.startsWith("/") && !uriRtspSetup.endsWith("/")) {
                track.request = "/" + track.request;
            }
            uriRtspSetup += track.request;
        }
        return uriRtspSetup;
    }

    private static void checkStatusCode(int code) throws IOException {
        switch (code) {
            case 200:
                break;
            case 401:
                throw new UnauthorizedException();
            default:
                throw new IOException("Invalid status code " + code);
        }
    }

    private static void readRtpData(
            @NonNull InputStream inputStream,
            @NonNull SdpInfo sdpInfo,
            @NonNull RtspClientListener listener,
            int keepAliveTimeout,
            @NonNull RtspClientKeepAliveListener keepAliveListener)
            throws IOException {
        byte[] data = EMPTY_ARRAY; // Usually not bigger than MTU = 15KB

        final RtpParser videoParser = (sdpInfo.videoTrack != null && sdpInfo.videoTrack.videoCodec == VIDEO_CODEC_H265 ?
                new RtpH265Parser() :
                new RtpH264Parser());
        final AacParser audioParser = (sdpInfo.audioTrack != null && sdpInfo.audioTrack.audioCodec == AUDIO_CODEC_AAC ?
                new AacParser(sdpInfo.audioTrack.mode) :
                null);

        byte[] nalUnitSps = (sdpInfo.videoTrack != null ? sdpInfo.videoTrack.sps : null);
        byte[] nalUnitPps = (sdpInfo.videoTrack != null ? sdpInfo.videoTrack.pps : null);
        byte[] nalUnitSei = EMPTY_ARRAY;
        byte[] nalUnitAud = EMPTY_ARRAY;
        int videoSeqNum = 0;

        long keepAliveSent = System.currentTimeMillis();
        Log.e(TAG,"this................."+keepAliveSent);
        while (mCloseConnect == false) {
            RtpHeaderParser.RtpHeader header = RtpHeaderParser.readHeader(inputStream);
            if (header == null) {
                continue;
            }
            if (header.payloadSize > data.length)
                data = new byte[header.payloadSize];

            NetUtils.readData(inputStream, data, 0, header.payloadSize);
            long l = System.currentTimeMillis();
            if (keepAliveTimeout > 0 && l - keepAliveSent > keepAliveTimeout) {
                keepAliveSent = l;
                keepAliveListener.onRtspKeepAliveRequested();
            }
            if (sdpInfo.videoTrack != null && header.payloadType == sdpInfo.videoTrack.payloadType) {
                videoSeqNum = header.sequenceNumber;

                byte[] nalUnit;
                if (header.extension == 1) {
                    int skipBytes = ((data[2] & 0xFF) << 8 | (data[3] & 0xFF)) * 4 + 4;
                    nalUnit = videoParser.processRtpPacketAndGetNalUnit(Arrays.copyOfRange(data, skipBytes, data.length),
                            header.payloadSize - skipBytes, header.marker == 1);
                } else {
                    nalUnit = videoParser.processRtpPacketAndGetNalUnit(data, header.payloadSize, header.marker == 1);
                }

                if (nalUnit != null) {
                    boolean isH265 = sdpInfo.videoTrack.videoCodec == VIDEO_CODEC_H265;
                    byte type = VideoCodecUtils.getNalUnitType(nalUnit, 0, nalUnit.length, isH265);
                    switch (type) {
                        case VideoCodecUtils.NAL_SPS:
                            nalUnitSps = nalUnit;
                            if (nalUnit.length > VideoCodecUtils.MAX_NAL_SPS_SIZE)
                                listener.onRtspVideoNalUnitReceived(nalUnit, 0, nalUnit.length, header.getTimestampMsec());
                            break;

                        case VideoCodecUtils.NAL_PPS:
                            nalUnitPps = nalUnit;
                            if (nalUnit.length > VideoCodecUtils.MAX_NAL_SPS_SIZE)
                                listener.onRtspVideoNalUnitReceived(nalUnit, 0, nalUnit.length, header.getTimestampMsec());
                            break;

                        case VideoCodecUtils.NAL_AUD:
                            nalUnitAud = nalUnit;
                            break;

                        case VideoCodecUtils.NAL_SEI:
                            nalUnitSei = nalUnit;
                            break;

                        case VideoCodecUtils.NAL_IDR_SLICE:
                            if (nalUnitSps != null && nalUnitPps != null) {
                                byte[] nalUnitSpsPpsIdr = new byte[nalUnitAud.length + nalUnitSps.length + nalUnitPps.length + nalUnitSei.length + nalUnit.length];
                                int offset = 0;
                                System.arraycopy(nalUnitSps, 0, nalUnitSpsPpsIdr, offset, nalUnitSps.length);
                                offset += nalUnitSps.length;
                                System.arraycopy(nalUnitPps, 0, nalUnitSpsPpsIdr, offset, nalUnitPps.length);
                                offset += nalUnitPps.length;
                                System.arraycopy(nalUnitAud, 0, nalUnitSpsPpsIdr, offset, nalUnitAud.length);
                                offset += nalUnitAud.length;
                                System.arraycopy(nalUnitSei, 0, nalUnitSpsPpsIdr, offset, nalUnitSei.length);
                                offset += nalUnitSei.length;
                                System.arraycopy(nalUnit, 0, nalUnitSpsPpsIdr, offset, nalUnit.length);
                                listener.onRtspVideoNalUnitReceived(nalUnitSpsPpsIdr, 0, nalUnitSpsPpsIdr.length, header.getTimestampMsec());
                                nalUnitSps = null;
                                nalUnitPps = null;
                                nalUnitSei = EMPTY_ARRAY;
                                nalUnitAud = EMPTY_ARRAY;
                                break;
                            }

                        default:
                            if (nalUnitSei.length == 0 && nalUnitAud.length == 0) {
                                listener.onRtspVideoNalUnitReceived(nalUnit, 0, nalUnit.length, header.getTimestampMsec());
                            } else {
                                byte[] nalUnitAudSeiSlice = new byte[nalUnitAud.length + nalUnitSei.length + nalUnit.length];
                                int offset = 0;
                                System.arraycopy(nalUnitAud, 0, nalUnitAudSeiSlice, offset, nalUnitAud.length);
                                offset += nalUnitAud.length;
                                System.arraycopy(nalUnitSei, 0, nalUnitAudSeiSlice, offset, nalUnitSei.length);
                                offset += nalUnitSei.length;
                                System.arraycopy(nalUnit, 0, nalUnitAudSeiSlice, offset, nalUnit.length);
                                listener.onRtspVideoNalUnitReceived(nalUnitAudSeiSlice, 0, nalUnitAudSeiSlice.length, header.getTimestampMsec());
                                nalUnitSei = EMPTY_ARRAY;
                                nalUnitAud = EMPTY_ARRAY;
                            }
                    }
                }
            } else if (sdpInfo.audioTrack != null && header.payloadType == sdpInfo.audioTrack.payloadType) {
                if (audioParser != null) {
                    byte[] sample = audioParser.processRtpPacketAndGetSample(data, header.payloadSize);
                    if (sample != null)
                        listener.onRtspAudioSampleReceived(sample, 0, sample.length, header.getTimestampMsec());
                }
            } else if (sdpInfo.applicationTrack != null && header.payloadType == sdpInfo.applicationTrack.payloadType) {
                listener.onRtspApplicationDataReceived(data, 0, header.payloadSize, header.getTimestampMsec());
            } else {
                if (DEBUG && header.payloadType >= 96 && header.payloadType <= 127)
                    Log.w(TAG, "Invalid RTP payload type " + header.payloadType);
            }
        }
    }

    private static void sendSimpleCommand(
            @NonNull String command,
            @NonNull OutputStream outputStream,
            @NonNull String request,
            int cSeq,
            @Nullable String userAgent,
            @Nullable String session,
            @Nullable String authToken)
            throws IOException {
        outputStream.write((command + " " + request + " RTSP/1.0" + CRLF).getBytes());
        if (authToken != null)
            outputStream.write(("Authorization: " + authToken + CRLF).getBytes());
        outputStream.write(("CSeq: " + cSeq + CRLF).getBytes());
        if (userAgent != null)
            outputStream.write(("User-Agent: " + userAgent + CRLF).getBytes());
        if (session != null)
            outputStream.write(("Session: " + session + CRLF).getBytes());
        outputStream.write(CRLF.getBytes());
        outputStream.flush();
    }

    private static void sendOptionsCommand(
            @NonNull OutputStream outputStream,
            @NonNull String request,
            int cSeq,
            @Nullable String userAgent,
            @Nullable String authToken)
            throws IOException {
        sendSimpleCommand("OPTIONS", outputStream, request, cSeq, userAgent, null, authToken);
    }

    private static void sendGetParameterCommand(
            @NonNull OutputStream outputStream,
            @NonNull String request,
            int cSeq,
            @Nullable String userAgent,
            @Nullable String session,
            @Nullable String authToken)
            throws IOException {
        sendSimpleCommand("GET_PARAMETER", outputStream, request, cSeq, userAgent, session, authToken);
    }

    private static void sendDescribeCommand(
            @NonNull OutputStream outputStream,
            @NonNull String request,
            int cSeq,
            @Nullable String userAgent,
            @Nullable String authToken)
            throws IOException {
        outputStream.write(("DESCRIBE " + request + " RTSP/1.0" + CRLF).getBytes());
        outputStream.write(("Accept: application/sdp" + CRLF).getBytes());
        if (authToken != null)
            outputStream.write(("Authorization: " + authToken + CRLF).getBytes());
        outputStream.write(("CSeq: " + cSeq + CRLF).getBytes());
        if (userAgent != null)
            outputStream.write(("User-Agent: " + userAgent + CRLF).getBytes());
        outputStream.write(CRLF.getBytes());
        outputStream.flush();
    }

    private static void sendTeardownCommand(
            @NonNull OutputStream outputStream,
            @NonNull String request,
            int cSeq,
            @Nullable String userAgent,
            @Nullable String authToken,
            @Nullable String session)
            throws IOException {
        outputStream.write(("TEARDOWN " + request + " RTSP/1.0" + CRLF).getBytes());
        if (authToken != null)
            outputStream.write(("Authorization: " + authToken + CRLF).getBytes());
        outputStream.write(("CSeq: " + cSeq + CRLF).getBytes());
        if (userAgent != null)
            outputStream.write(("User-Agent: " + userAgent + CRLF).getBytes());
        if (session != null)
            outputStream.write(("Session: " + session + CRLF).getBytes());
        outputStream.write(CRLF.getBytes());
        outputStream.flush();
    }

    private static void sendSetupCommand(
            @NonNull OutputStream outputStream,
            @NonNull String request,
            int cSeq,
            @Nullable String userAgent,
            @Nullable String authToken,
            @Nullable String session,
            @NonNull String interleaved)
            throws IOException {
        outputStream.write(("SETUP " + request + " RTSP/1.0" + CRLF).getBytes());
        outputStream.write(("Transport: RTP/AVP/TCP;unicast;interleaved=" + interleaved + CRLF).getBytes());
        if (authToken != null)
            outputStream.write(("Authorization: " + authToken + CRLF).getBytes());
        outputStream.write(("CSeq: " + cSeq + CRLF).getBytes());
        if (userAgent != null)
            outputStream.write(("User-Agent: " + userAgent + CRLF).getBytes());
        if (session != null)
            outputStream.write(("Session: " + session + CRLF).getBytes());
        outputStream.write(CRLF.getBytes());
        outputStream.flush();
    }

    private static void sendPlayCommand(
            @NonNull OutputStream outputStream,
            @NonNull String request,
            int cSeq,
            @Nullable String userAgent,
            @Nullable String authToken,
            @NonNull String session)
            throws IOException {
        outputStream.write(("PLAY " + request + " RTSP/1.0" + CRLF).getBytes());
        outputStream.write(("Range: npt=0.000-" + CRLF).getBytes());
        if (authToken != null)
            outputStream.write(("Authorization: " + authToken + CRLF).getBytes());
        outputStream.write(("CSeq: " + cSeq + CRLF).getBytes());
        if (userAgent != null)
            outputStream.write(("User-Agent: " + userAgent + CRLF).getBytes());
        outputStream.write(("Session: " + session + CRLF).getBytes());
        outputStream.write(CRLF.getBytes());
        outputStream.flush();
    }

    private int readResponseStatusCode(@NonNull InputStream inputStream) throws IOException {
        String line;
        byte[] rtspHeader = "RTSP/1.0 ".getBytes();
        while (readUntilBytesFound(inputStream, rtspHeader) && (line = readLine(inputStream)) != null) {
            int indexCode = line.indexOf(' ');
            String code = line.substring(0, indexCode);
            try {
                int statusCode = Integer.parseInt(code);
                return statusCode;
            } catch (NumberFormatException e) {
            }
        }
        return -1;
    }

    @NonNull
    private ArrayList<Pair<String, String>> readResponseHeaders(@NonNull InputStream inputStream) throws IOException {
        ArrayList<Pair<String, String>> headers = new ArrayList<>();
        String line;
        while (!TextUtils.isEmpty(line = readLine(inputStream))) {
            if (debug)
                Log.d(TAG_DEBUG, "" + line);
            if (CRLF.equals(line)) {
                return headers;
            } else {
                String[] pairs = line.split(":", 2);
                if (pairs.length == 2) {
                    headers.add(Pair.create(pairs[0].trim(), pairs[1].trim()));
                }
            }
        }
        return headers;
    }
    @NonNull
    private static Track[] getTracksFromDescribeParams(@NonNull List<Pair<String, String>> params) {
        Track[] tracks = new Track[3];
        Track currentTrack = null;
        for (Pair<String, String> param: params) {
            switch (param.first) {
                case "m":
                    if (param.second.startsWith("video")) {
                        currentTrack = new VideoTrack();
                        tracks[0] = currentTrack;
                    } else if (param.second.startsWith("audio")) {
                        currentTrack = new AudioTrack();
                        tracks[1] = currentTrack;
                    } else if (param.second.startsWith("application")) {
                        currentTrack = new ApplicationTrack();
                        tracks[2] = currentTrack;

                    } else if (param.second.startsWith("text")) {
                        Log.w(TAG, "Media track 'text' is not supported");

                    } else if (param.second.startsWith("message")) {
                        Log.w(TAG, "Media track 'message' is not supported");

                    } else {
                        currentTrack = null;
                    }

                    if (currentTrack != null) {
                        // m=<media> <port>/<number of ports> <proto> <fmt> ...
                        String[] values = TextUtils.split(param.second, " ");
                        try {
                            currentTrack.payloadType = (values.length > 3 ? Integer.parseInt(values[3]) : -1);
                        } catch (Exception e) {
                            currentTrack.payloadType = -1;
                        }
                        if (currentTrack.payloadType == -1)
                            Log.e(TAG, "Failed to get payload type from \"m=" + param.second + "\"");
                    }
                    break;

                case "a":
                    if (currentTrack != null) {
                        if (param.second.startsWith("control:")) {
                            currentTrack.request = param.second.substring(8);
                        } else if (param.second.startsWith("fmtp:")) {
                            if (currentTrack instanceof VideoTrack) {
                                updateVideoTrackFromDescribeParam((VideoTrack)tracks[0], param);
                            } else if (currentTrack instanceof AudioTrack) {
                                updateAudioTrackFromDescribeParam((AudioTrack)tracks[1], param);
                            }
                        } else if (param.second.startsWith("rtpmap:")) {
                            String[] values = TextUtils.split(param.second, " ");
                            if (currentTrack instanceof VideoTrack) {
                                if (values.length > 1) {
                                    values = TextUtils.split(values[1], "/");
                                    if (values.length > 0) {
                                        switch (values[0].toLowerCase()) {
                                            case "h264" -> ((VideoTrack) tracks[0]).videoCodec = VIDEO_CODEC_H264;
                                            case "h265" -> ((VideoTrack) tracks[0]).videoCodec = VIDEO_CODEC_H265;
                                            default -> Log.w(TAG, "Unknown video codec \"" + values[0] + "\"");
                                        }
                                        Log.i(TAG, "Video: " + values[0]);
                                        //pradeep.....
                                    }
                                }
                            } else if (currentTrack instanceof AudioTrack) {
                                if (values.length > 1) {
                                    AudioTrack track = ((AudioTrack) tracks[1]);
                                    values = TextUtils.split(values[1], "/");
                                    if (values.length > 1) {
                                        switch (values[0].toLowerCase()) {
                                            case "mpeg4-generic" -> track.audioCodec = AUDIO_CODEC_AAC;
                                            case "opus" -> track.audioCodec = AUDIO_CODEC_OPUS;
                                            default -> {
                                                Log.w(TAG, "Unknown audio codec \"" + values[0] + "\"");
                                                track.audioCodec = AUDIO_CODEC_UNKNOWN;
                                            }
                                        }
                                        track.sampleRateHz = Integer.parseInt(values[1]);
                                        track.channels = values.length > 2 ? Integer.parseInt(values[2]) : 1;
                                    }
                                }
                            }
                        }
                    }
                    break;
            }
        }
        return tracks;
    }
    @NonNull
    private static List<Pair<String, String>> getDescribeParams(@NonNull String text) {
        ArrayList<Pair<String, String>> list = new ArrayList<>();
        String[] params = TextUtils.split(text, "\r\n");
        for (String param : params) {
            int i = param.indexOf('=');
            if (i > 0) {
                String name = param.substring(0, i).trim();
                String value = param.substring(i + 1);
                list.add(Pair.create(name, value));
            }
        }
        return list;
    }

    @NonNull
    private static SdpInfo getSdpInfoFromDescribeParams(@NonNull List<Pair<String, String>> params) {
        SdpInfo sdpInfo = new SdpInfo();

        Track[] tracks = getTracksFromDescribeParams(params);
        sdpInfo.videoTrack = ((VideoTrack)tracks[0]);
        sdpInfo.audioTrack = ((AudioTrack)tracks[1]);
        sdpInfo.applicationTrack = ((ApplicationTrack)tracks[2]);

        for (Pair<String, String> param : params) {
            switch (param.first) {
                case "s" -> sdpInfo.sessionName = param.second;
                case "i" -> sdpInfo.sessionDescription = param.second;
            }
        }
        return sdpInfo;
    }
    @Nullable
    private static List<Pair<String, String>> getSdpAParams(@NonNull Pair<String, String> param) {
        if (param.first.equals("a") && param.second.startsWith("fmtp:") && param.second.length() > 8) { //
            String value = param.second.substring(8).trim(); // fmtp can be '96' (2 chars) and '127' (3 chars)
            String[] paramsA = TextUtils.split(value, ";");
            ArrayList<Pair<String, String>> retParams = new ArrayList<>();
            for (String paramA: paramsA) {
                paramA = paramA.trim();
                int i = paramA.indexOf("=");
                if (i != -1)
                    retParams.add(
                            Pair.create(
                                    paramA.substring(0, i),
                                    paramA.substring(i + 1)));
            }
            return retParams;
        }
        return null;
    }

    @NonNull
    private static byte[] getNalUnitFromSprop(String nalBase64) {
        byte[] nal = Base64.decode(nalBase64, Base64.NO_WRAP);
        byte[] nalWithStart = new byte[nal.length + 4];
        nalWithStart[0] = 0;
        nalWithStart[1] = 0;
        nalWithStart[2] = 0;
        nalWithStart[3] = 1;
        System.arraycopy(nal, 0, nalWithStart, 4, nal.length);
        return nalWithStart;
    }

    private static void updateVideoTrackFromDescribeParam(@NonNull VideoTrack videoTrack, @NonNull Pair<String, String> param) {
        List<Pair<String, String>> params = getSdpAParams(param);
        if (params != null) {
            for (Pair<String, String> pair: params) {
                switch (pair.first.toLowerCase()) {
                    case "sprop-sps" -> {
                        videoTrack.sps = getNalUnitFromSprop(pair.second);
                    }
                    case "sprop-pps" -> {
                        videoTrack.pps = getNalUnitFromSprop(pair.second);
                    }
                    case "sprop-vps" -> {
                        videoTrack.vps = getNalUnitFromSprop(pair.second);
                    }
                    case "sprop-parameter-sets" -> {
                        String[] paramsSpsPps = TextUtils.split(pair.second, ",");
                        if (paramsSpsPps.length > 1) {
                            videoTrack.sps = getNalUnitFromSprop(paramsSpsPps[0]);
                            videoTrack.pps = getNalUnitFromSprop(paramsSpsPps[1]);
                        }
                    }
                }
            }
        }
    }

    @NonNull
    private static byte[] getBytesFromHexString(@NonNull String config) {
        return new BigInteger(config ,16).toByteArray();
    }

    private static void updateAudioTrackFromDescribeParam(@NonNull AudioTrack audioTrack, @NonNull Pair<String, String> param) {
       List<Pair<String, String>> params = getSdpAParams(param);
        if (params != null) {
            for (Pair<String, String> pair: params) {
                switch (pair.first.toLowerCase()) {
                    case "mode" -> audioTrack.mode = pair.second;
                    case "config" -> audioTrack.config = getBytesFromHexString(pair.second);
                }
            }
        }
    }
    @Nullable
    private static String getHeaderContentBase(@NonNull ArrayList<Pair<String, String>> headers) {
        String contentBase = getHeader(headers, "content-base");
        if (!TextUtils.isEmpty(contentBase)) {
            return contentBase;
        }
        return null;
    }

    private static int getHeaderContentLength(@NonNull ArrayList<Pair<String, String>> headers) {
        String length = getHeader(headers, "content-length");
        if (!TextUtils.isEmpty(length)) {
            try {
                return Integer.parseInt(length);
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static int getSupportedCapabilities(@NonNull ArrayList<Pair<String, String>> headers) {
        for (Pair<String, String> head: headers) {
            String h = head.first.toLowerCase();
            if ("public".equals(h)) {
                int mask = 0;
                String[] tokens = TextUtils.split(head.second.toLowerCase(), ",");
                for (String token: tokens) {
                    switch (token.trim()) {
                        case "options" -> mask |= RTSP_CAPABILITY_OPTIONS;
                        case "describe" -> mask |= RTSP_CAPABILITY_DESCRIBE;
                        case "announce" -> mask |= RTSP_CAPABILITY_ANNOUNCE;
                        case "setup" -> mask |= RTSP_CAPABILITY_SETUP;
                        case "play" -> mask |= RTSP_CAPABILITY_PLAY;
                        case "record" -> mask |= RTSP_CAPABILITY_RECORD;
                        case "pause" -> mask |= RTSP_CAPABILITY_PAUSE;
                        case "teardown" -> mask |= RTSP_CAPABILITY_TEARDOWN;
                        case "set_parameter" -> mask |= RTSP_CAPABILITY_SET_PARAMETER;
                        case "get_parameter" -> mask |= RTSP_CAPABILITY_GET_PARAMETER;
                        case "redirect" -> mask |= RTSP_CAPABILITY_REDIRECT;
                    }
                }
                return mask;
            }
        }
        return RTSP_CAPABILITY_NONE;
    }

    @Nullable
    private static Pair<String, String> getHeaderWwwAuthenticateDigestRealmAndNonce(@NonNull ArrayList<Pair<String, String>> headers) {
        for (Pair<String, String> head: headers) {
            String h = head.first.toLowerCase();
            if ("www-authenticate".equals(h) && head.second.toLowerCase().startsWith("digest")) {
                String v = head.second.substring(7).trim();
                int begin, end;

                begin = v.indexOf("realm=");
                begin = v.indexOf('"', begin) + 1;
                end = v.indexOf('"', begin);
                String digestRealm = v.substring(begin, end);

                begin = v.indexOf("nonce=");
                begin = v.indexOf('"', begin)+1;
                end = v.indexOf('"', begin);
                String digestNonce = v.substring(begin, end);

                return Pair.create(digestRealm, digestNonce);
            }
        }
        return null;
    }

    @Nullable
    private static String getHeaderWwwAuthenticateBasicRealm(@NonNull ArrayList<Pair<String, String>> headers) {
        for (Pair<String, String> head: headers) {
            String h = head.first.toLowerCase();
            String v = head.second.toLowerCase();
            if ("www-authenticate".equals(h) && v.startsWith("basic")) {
                v = v.substring(6).trim();
                String[] tokens = TextUtils.split(v, "\"");
                if (tokens.length > 2)
                    return tokens[1];
            }
        }
        return null;
    }
    @NonNull
    private static String getBasicAuthHeader(@Nullable String username, @Nullable String password) {
        String auth = (username == null ? "" : username) + ":" + (password == null ? "" : password);
        return "Basic " + new String(Base64.encode(auth.getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP));
    }
    @Nullable
    private static String getDigestAuthHeader(
            @Nullable String username,
            @Nullable String password,
            @NonNull String method,
            @NonNull String digestUri,
            @NonNull String realm,
            @NonNull String nonce) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] ha1;

            if (username == null)
                username = "";
            if (password == null)
                password = "";
            md.update(username.getBytes(StandardCharsets.ISO_8859_1));
            md.update((byte) ':');
            md.update(realm.getBytes(StandardCharsets.ISO_8859_1));
            md.update((byte) ':');
            md.update(password.getBytes(StandardCharsets.ISO_8859_1));
            ha1 = md.digest();
            md.reset();
            md.update(method.getBytes(StandardCharsets.ISO_8859_1));
            md.update((byte) ':');
            md.update(digestUri.getBytes(StandardCharsets.ISO_8859_1));
            byte[] ha2 = md.digest();
            md.update(getHexStringFromBytes(ha1).getBytes(StandardCharsets.ISO_8859_1));
            md.update((byte) ':');
            md.update(nonce.getBytes(StandardCharsets.ISO_8859_1));
            md.update((byte) ':');
            md.update(getHexStringFromBytes(ha2).getBytes(StandardCharsets.ISO_8859_1));
            String response = getHexStringFromBytes(md.digest());

            return "Digest username=\"" + username + "\", realm=\"" + realm + "\", nonce=\"" + nonce + "\", uri=\"" + digestUri + "\", response=\"" + response + "\"";
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @NonNull
    private static String getHexStringFromBytes(@NonNull byte[] bytes) {
        StringBuilder buf = new StringBuilder();
        for (byte b : bytes)
            buf.append(String.format("%02x", b));
        return buf.toString();
    }

    @NonNull
    private static String readContentAsText(@NonNull InputStream inputStream, int length) throws IOException {
        if (length <= 0)
            return "";
        byte[] b = new byte[length];
        int read = readData(inputStream, b, 0, length);
        return new String(b, 0, read);
    }
    public static boolean memcmp(
            @NonNull byte[] source1,
            int offsetSource1,
            @NonNull byte[] source2,
            int offsetSource2,
            int num) {
        if (source1.length - offsetSource1 < num)
            return false;
        if (source2.length - offsetSource2 < num)
            return false;

        for (int i = 0; i < num; i++) {
            if (source1[offsetSource1 + i] != source2[offsetSource2 + i])
                return false;
        }
        return true;
    }

    private static void shiftLeftArray(@NonNull byte[] array, int num) {
        if (num - 1 >= 0)
            System.arraycopy(array, 1, array, 0, num - 1);
    }

    private boolean readUntilBytesFound(@NonNull InputStream inputStream, @NonNull byte[] array) throws IOException {
        byte[] buffer = new byte[array.length];
        if (NetUtils.readData(inputStream, buffer, 0, buffer.length) != buffer.length)
            return false;

        while (true) {
            if (memcmp(buffer, 0, array, 0, buffer.length)) {
                return true;
            }
            shiftLeftArray(buffer, buffer.length);
            if (NetUtils.readData(inputStream, buffer, buffer.length - 1, 1) != 1) {
                return false; // EOF
            }
        }
    }

    @Nullable
    private String readLine(@NonNull InputStream inputStream) throws IOException {
        byte[] bufferLine = new byte[MAX_LINE_SIZE];
        int offset = 0;
        int readBytes;
        do {
            if (offset >= MAX_LINE_SIZE) {
                throw new NoResponseHeadersException();
            }
            readBytes = inputStream.read(bufferLine, offset, 1);
            if (readBytes == 1) {
                if (offset > 0 && bufferLine[offset] == '\n') {
                    if (offset == 1)
                        return "";
                    return new String(bufferLine, 0, offset-1);
                } else {
                    offset++;
                }
            }
        } while (readBytes > 0 );
        return null;
    }

    private static int readData(@NonNull InputStream inputStream, @NonNull byte[] buffer, int offset, int length) throws IOException {
        if (DEBUG) Log.v(TAG, "readData(offset=" + offset + ", length=" + length + ")");
        int readBytes;
        int totalReadBytes = 0;
        do {
            readBytes = inputStream.read(buffer, offset + totalReadBytes, length - totalReadBytes);
            if (readBytes > 0)
                totalReadBytes += readBytes;
        } while (readBytes >= 0 && totalReadBytes < length);
        return totalReadBytes;
    }

    private static void dumpHeaders(@NonNull ArrayList<Pair<String, String>> headers) {
        if (true) {
            for (Pair<String, String> head : headers) {
                Log.d(TAG, head.first + ": " + head.second);
            }
        }
    }

    @Nullable
    private static String getHeader(@NonNull ArrayList<Pair<String, String>> headers, @NonNull String header) {
        for (Pair<String, String> head: headers) {
            String h = head.first.toLowerCase();
            if (header.toLowerCase().equals(h)) {
                return head.second;
            }
        }
        return null;
    }

    public static class Builder {

        private static final String DEFAULT_USER_AGENT = "Lavf58.29.100";

        private final @NonNull Socket rtspSocket;
        private final @NonNull String uriRtsp;
        private final @NonNull RtspClientListener listener;
        private boolean requestVideo = true;
        private boolean requestAudio = true;
        private boolean requestApplication = true;
        private boolean debug = false;
        private @Nullable String username = null;
        private @Nullable String password = null;
        private @Nullable String userAgent = DEFAULT_USER_AGENT;

        public Builder(
                @NonNull Socket rtspSocket,
                @NonNull String uriRtsp,
                @NonNull RtspClientListener listener) {
            this.rtspSocket = rtspSocket;
            this.uriRtsp = uriRtsp;
            this.listener = listener;
        }

        @NonNull
        public Builder withDebug(boolean debug) {
            this.debug = debug;
            return this;
        }

        @NonNull
        public Builder withCredentials(@Nullable String username, @Nullable String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        @NonNull
        public Builder withUserAgent(@Nullable String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        @NonNull
        public Builder requestVideo(boolean requestVideo) {
            this.requestVideo = requestVideo;
            return this;
        }

        @NonNull
        public Builder requestAudio(boolean requestAudio) {
            this.requestAudio = requestAudio;
            return this;
        }

        @NonNull
        public Builder requestApplication(boolean requestApplication) {
            this.requestApplication = requestApplication;
            return this;
        }

        @NonNull
        public RtspClient build() {
            return new RtspClient(this);
        }
    }

    class LoggerOutputStream extends BufferedOutputStream {
        private boolean logging = true;

        public LoggerOutputStream(@NonNull OutputStream out) {
            super(out);
        }

        public synchronized void setLogging(boolean logging) {
            this.logging = logging;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            if (logging)
                Log.i(RtspClient.TAG_DEBUG, new String(b, off, len));
        }
    }
}
