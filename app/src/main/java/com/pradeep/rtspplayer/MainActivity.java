package com.pradeep.rtspplayer;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.pradeep.rtspplayer.databinding.ActivityMainBinding;
import com.pradeep.rtspplayer.utils.NetUtils;

import java.net.Socket;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback{
    private ActivityMainBinding binding;
    private FBoxCCTVPlay mPlayer;
    private String TAG = "MainActivity";
    private RtspClient.RtspClientListener rtspClientListener;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private Context mContext;
    private boolean mPassword;
    private  RtspClient rtspClient;
    private boolean mStopPlayer = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mSurfaceView = binding.surfaceView;
        mPassword = false;
        mSurfaceHolder = mSurfaceView.getHolder();
        mContext = this;
        mStopPlayer = false;
        mSurfaceHolder.addCallback(this);
        backgroundListener();
        binding.bnStartStopSurface.setOnClickListener(View-> {
            if(binding.bnStartStopSurface.getText().toString().equalsIgnoreCase("Start")) {
                if (binding.etRtspRequest.getText().toString().length() < 10) {
                    Toast.makeText(mContext, "Please enter ip address of the RTSP camera", Toast.LENGTH_SHORT).show();
                } else if (binding.etRtspUsername.getText().toString().length() < 2) {
                    Toast.makeText(mContext, "Please enter username of the RTSP camera", Toast.LENGTH_SHORT).show();
                } else if (binding.etRtspPassword.getText().toString().length() < 2) {
                    Toast.makeText(mContext, "Please enter password of the RTSP camera", Toast.LENGTH_SHORT).show();
                } else {
                    mStopPlayer = false;
                    startStreaming();
                }
            } else {
                mStopPlayer = true;
                stopPlayer();
            }
        });
        binding.appBar.backRegister.setOnClickListener(View-> {
            stopPlayer();
            finish();
        });

    }

    private void stopPlayer() {
        try {
            rtspClient.closeSocketConnect();
            mPlayer.stopPlayer();
            mPlayer = null;
            ((MainActivity) mContext).runOnUiThread(new Runnable() {
                public void run() {
                    Log.e(TAG,"start setting.............");
                    binding.bnStartStopSurface.setText("Start");
                }
            });
            binding.surfaceView.setVisibility(android.view.View.GONE);
            binding.surfaceView.setVisibility(android.view.View.VISIBLE);
            surfaceCreated(binding.surfaceView.getHolder());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void backgroundListener() {
        new Thread() {
            @Override
            public void run() {
                super.run();
                Log.i(TAG, "1Thread started");
                rtspClientListener = new RtspClient.RtspClientListener() {
                    @Override
                    public void onRtspConnecting() {
                        Log.i(TAG, "onrtsp connecting......");
                    }
                    @Override
                    public void onRtspConnected(@NonNull RtspClient.SdpInfo sdpInfo) {
                        Log.e(TAG,"........"+sdpInfo.sessionDescription);
                        Log.e(TAG,"........"+sdpInfo.applicationTrack);
                        Log.e(TAG,"........"+sdpInfo.videoTrack);
                        Log.e(TAG,"........"+sdpInfo.audioTrack);
                        if(sdpInfo.videoTrack.videoCodec == 1) {
                            ((MainActivity) mContext).runOnUiThread(new Runnable() {
                                public void run() {
                                    mPlayer = new FBoxCCTVPlay(mSurfaceHolder.getSurface(), "video/hevc", 340, 320);
                                    if (mPlayer != null) {
                                        mPlayer.start();
                                    }
                                }
                            });
                        } else {
                            ((MainActivity) mContext).runOnUiThread(new Runnable() {
                                public void run() {
                                    mPlayer = new FBoxCCTVPlay(mSurfaceHolder.getSurface(), "video/avc", 340, 320);
                                    if (mPlayer != null) {
                                        mPlayer.start();
                                    }
                                }
                            });
                        }
                    }

                    @Override
                    public void onRtspVideoNalUnitReceived(@NonNull byte[] data, int offset, int length, long timestamp) {
                        ((MainActivity) mContext).runOnUiThread(new Runnable() {
                            public void run() {
                                if(mStopPlayer == false && !binding.bnStartStopSurface.getText().toString().equalsIgnoreCase("Stop")) {
                                    binding.bnStartStopSurface.setText("Stop");
                                }
                            }
                        });
                        if(mPlayer != null) {
                            mPlayer.putData(data, data.length);
                        }
                    }

                    @Override
                    public void onRtspAudioSampleReceived(@NonNull byte[] data, int offset, int length, long timestamp) {
                    }

                    @Override
                    public void onRtspApplicationDataReceived(@NonNull byte[] data, int offset, int length, long timestamp) {

                    }

                    @Override
                    public void onRtspDisconnecting() {
                        Log.e(TAG,"onRtspDisconnecting");
                    }

                    @Override
                    public void onRtspDisconnected() {
                        Log.e(TAG,"onRtspDisconnecting");
                    }

                    @Override
                    public void onRtspFailedUnauthorized() {
                        Log.e(TAG,"onRtspDisconnecting");
                        mPassword = true;
                        ((MainActivity) mContext).runOnUiThread(new Runnable() {
                            public void run() {
                                Toast.makeText(mContext, "Please enter valid user name and password", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onRtspFailed(@Nullable String message) {
                        if (!mPassword) {
                            ((MainActivity) mContext).runOnUiThread(new Runnable() {
                                public void run() {
                                    Toast.makeText(mContext, "Please check camera or ip address correct", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                        mPassword = false;
                    }
                };
            }
        }.start();
    }

    private void startStreaming() {
        new Thread() {
            @Override
            public void run() {
                super.run();
                Log.i(TAG, "Thread started");
                Socket socket = null;
                try {
                    //"rtsp://192.168.0.115:554/ch0_0.264"
                    Uri uri = Uri.parse("rtsp://"+binding.etRtspRequest.getText().toString()+":554/ch0_0.264");
                    int port = (uri.getPort() == -1) ? 554 : uri.getPort();
                    socket = NetUtils.createSocketAndConnect(uri.getHost(), port, 5000);
                    rtspClient = new RtspClient.Builder(socket, uri.toString(), rtspClientListener)
                            .requestVideo(true)
                            .requestAudio(false)
                            .requestApplication(true)
                            .withDebug(true)
                            .withUserAgent("user_access")
                            .withCredentials(binding.etRtspUsername.getText().toString(), binding.etRtspPassword.getText().toString())
                            .build();

                    rtspClient.execute();
                } catch (Exception e) {
                    e.printStackTrace();
                    binding.getRoot().post(new Runnable() {
                        @Override
                        public void run() {
                            rtspClientListener.onRtspFailed(e.getMessage());
                        }
                    });
                } finally {
                    try {
                        NetUtils.closeSocket(socket);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                Log.i(TAG, "Thread stopped");
            }
        }.start();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int i, int i1, int i2) {

    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {

    }
}