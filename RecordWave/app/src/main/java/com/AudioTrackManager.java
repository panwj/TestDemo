package com;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import com.czt.mp3recorder.PCMFormat;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;

import utils.SharedPreferencesUtil;

/**
 * Created by panwenjuan on 17-6-2.
 */
public class AudioTrackManager {
    //=======================AudioRecord Default Settings=======================
    private static final int DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    /**
     * 以下三项为默认配置参数。Google Android文档明确表明只有以下3个参数是可以在所有设备上保证支持的。
     */
    private static final int DEFAULT_SAMPLING_RATE = 44100;//模拟器仅支持从麦克风输入8kHz采样率
    private static final int DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    /**
     * 下面是对此的封装
     * private static final int DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
     */
    private static final PCMFormat DEFAULT_AUDIO_FORMAT = PCMFormat.PCM_16BIT;

    private AudioTrack audioTrack;
    private DataInputStream dis;
    private Thread recordThread;
    private boolean isStart = false;
    private static AudioTrackManager mInstance;
    private int bufferSize;
    private static final String TAG = "AudioTrackManager";
    private Context mContext;

    public AudioTrackManager(Context context) {
        mContext = context;
//        bufferSize = AudioTrack.getMinBufferSize(41000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
//        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 41000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2, AudioTrack.MODE_STREAM);
    }

    /**
     * 获取单例引用
     *
     * @return
     */
    public static AudioTrackManager getInstance(Context context) {
        if (mInstance == null) {
            synchronized (AudioTrackManager.class) {
                if (mInstance == null) {
                    mInstance = new AudioTrackManager(context);
                }
            }
        }
        return mInstance;
    }

    /**
     * 销毁线程方法
     */
    private void destroyThread() {
        Log.d(TAG, "destroyThread()");
        try {
            isStart = false;
            if (null != recordThread && Thread.State.RUNNABLE == recordThread.getState()) {
                try {
                    Thread.sleep(500);
                    recordThread.interrupt();
                } catch (Exception e) {
                    recordThread = null;
                }
            }
            recordThread = null;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            recordThread = null;
        }
    }

    /**
     * 启动播放线程
     */
    private void startThread() {
        Log.d(TAG, "startThread()");
        destroyThread();
        isStart = true;
        if (recordThread == null) {
            recordThread = new Thread(recordRunnable);
            recordThread.start();
        }
    }

    /**
     * 播放线程
     */
    Runnable recordRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Log.d(TAG, "real run start");
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                int test_source = (int) SharedPreferencesUtil.get(mContext, "source", DEFAULT_AUDIO_SOURCE);
                Log.d(TAG, "source = " + test_source);
                int test_sampling_rate = (int) SharedPreferencesUtil.get(mContext, "hz", DEFAULT_SAMPLING_RATE);
                int test_channel_congif = (int) SharedPreferencesUtil.get(mContext, "channel", DEFAULT_CHANNEL_CONFIG);
                int test_bit = (int) SharedPreferencesUtil.get(mContext, "encoding", DEFAULT_AUDIO_FORMAT.getAudioFormat());
                Log.d(TAG, "test_source = " + test_source + "  test_sampling_rate = " + test_sampling_rate
                        + "  test_channel_congif = " + test_channel_congif + "  test_bit = " + test_bit);

                bufferSize = AudioTrack.getMinBufferSize(test_sampling_rate, test_channel_congif, test_bit);
                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, test_sampling_rate, test_channel_congif, test_bit, bufferSize * 2, AudioTrack.MODE_STREAM);
                byte[] tempBuffer = new byte[bufferSize];
                int readCount = 0;
                if (Build.VERSION.SDK_INT >= 21) {
                    audioTrack.setVolume(1);
                }
                while (dis.available() > 0) {
                    readCount= dis.read(tempBuffer);
                    if (readCount == AudioTrack.ERROR_INVALID_OPERATION || readCount == AudioTrack.ERROR_BAD_VALUE) {
                        continue;
                    }
                    if (readCount != 0 && readCount != -1) {
                        audioTrack.play();
                        audioTrack.write(tempBuffer, 0, readCount);
                        if (mAudioTrackCallback != null) {
                            mAudioTrackCallback.onStart();
                        }
                    }
                }
                stopPlay();
            } catch (Exception e) {
                e.printStackTrace();
                if (mAudioTrackCallback != null) {
                    mAudioTrackCallback.onFailed();
                }
            }
        }

    };

    /**
     * 播放文件
     *
     * @param path
     * @throws Exception
     */
    private void setPath(String path) throws Exception {
        File file = new File(path);
        dis = new DataInputStream(new FileInputStream(file));
    }

    /**
     * 启动播放
     *
     * @param path
     */
    public void startPlay(String path) {
        Log.d("zzz", "startPlay()  path = " + path);
        try {
            setPath(path);
            startThread();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 停止播放
     */
    public void stopPlay() {
        Log.d(TAG, "stopPlay()");
        try {
            destroyThread();
            if (audioTrack != null) {
                if (audioTrack.getState() == AudioRecord.STATE_INITIALIZED) {
                    Log.d("zzz", "audio track stop");
                    audioTrack.stop();
                }
                if (audioTrack != null) {
                    audioTrack.release();
                    Log.d("zzz", "audio track release");
                }
            }
            if (dis != null) {
                dis.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (mAudioTrackCallback != null) {
                mAudioTrackCallback.onStop();
            }
        }
    }

    private AudioTrackCallback mAudioTrackCallback;
    public void setAudioTrackCallback(AudioTrackCallback callback) {
        mAudioTrackCallback = callback;
    }
    public interface AudioTrackCallback{

        void onStart();
        void onStop();

        void onFailed();
    }
}
