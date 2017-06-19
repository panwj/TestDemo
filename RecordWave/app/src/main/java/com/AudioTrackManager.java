package com;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Created by panwenjuan on 17-6-2.
 */
public class AudioTrackManager {
    private AudioTrack audioTrack;
    private DataInputStream dis;
    private Thread recordThread;
    private boolean isStart = false;
    private static AudioTrackManager mInstance;
    private int bufferSize;

    public AudioTrackManager() {
//        bufferSize = AudioTrack.getMinBufferSize(41000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
//        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 41000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2, AudioTrack.MODE_STREAM);
    }

    /**
     * 获取单例引用
     *
     * @return
     */
    public static AudioTrackManager getInstance() {
        if (mInstance == null) {
            synchronized (AudioTrackManager.class) {
                if (mInstance == null) {
                    mInstance = new AudioTrackManager();
                }
            }
        }
        return mInstance;
    }

    /**
     * 销毁线程方法
     */
    private void destroyThread() {
        Log.d("zzz", "destroyThread()");
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
        Log.d("zzz", "startThread()");
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
                Log.d("zzz", "real run start");
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                bufferSize = AudioTrack.getMinBufferSize(41000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 41000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2, AudioTrack.MODE_STREAM);
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
                        Log.d("zzz", "write()");
                        audioTrack.write(tempBuffer, 0, readCount);
                    }
                }
                stopPlay();
            } catch (Exception e) {
                e.printStackTrace();
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
        Log.d("zzz", "stopPlay()");
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
        }
    }
}
