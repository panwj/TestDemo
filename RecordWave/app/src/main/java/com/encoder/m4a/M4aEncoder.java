package com.encoder.m4a;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import utils.SharedPreferencesUtil;

/**
 * Created by panwenjuan on 17-6-19.
 */
public class M4aEncoder {

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
    private static final int DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static final String TAG = "M4aEncoder";
    private static M4aEncoder mM4aEncoder = null;
    private static Context mContext;

    private static int test_sampling_rate = DEFAULT_SAMPLING_RATE;
    private static int test_channel_config = DEFAULT_CHANNEL_CONFIG;
    private static int test_bit = DEFAULT_AUDIO_FORMAT;

    public M4aEncoder(Context context) {
        this.mContext = context;
    }

    public synchronized static M4aEncoder getInstance(Context context) {
        if (mM4aEncoder == null) {
            mM4aEncoder = new M4aEncoder(context);
        }
        test_sampling_rate = (int) SharedPreferencesUtil.get(mContext, "hz", DEFAULT_SAMPLING_RATE);
        test_channel_config = (int) SharedPreferencesUtil.get(mContext, "channel", DEFAULT_CHANNEL_CONFIG);
        test_bit = (int) SharedPreferencesUtil.get(mContext, "encoding", DEFAULT_AUDIO_FORMAT);
        return mM4aEncoder;
    }

    public void encodeToM4a(InputStream inputStream, final String outputPath) {
        if (Build.VERSION.SDK_INT >= 18) {
            encodeSingleFile(inputStream, outputPath);
        } else {
            Toast.makeText(mContext, "该方式仅支持api>=18", Toast.LENGTH_SHORT).show();
        }
    }

    public void encodeToM4a(String inputPath, final String outputPath) {
        Log.d(TAG, "encodeToM4a() enter");
        if (Build.VERSION.SDK_INT >= 18) {
            try {
                FileInputStream fis = new FileInputStream(inputPath);
                encodeSingleFile(fis, outputPath);
            } catch (IOException e) {

            }
        } else {
            Toast.makeText(mContext, "该方式仅支持api>=18", Toast.LENGTH_SHORT).show();
        }
        Log.d(TAG, "encodeToM4a() exit");
    }

    private void encodeSingleFile(InputStream inputStream, final String outputPath) {
        Log.d("zzz", "outputPath = " + outputPath);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(encodeTask(1, inputStream, outputPath));
    }

    private void encodeMultipleFiles(InputStream inputStream, final String outputPath) {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(encodeTask(10, inputStream, outputPath));
    }

    private Runnable encodeTask(final int numFiles, final InputStream inputStream, final String outputPath) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "test_bit = " + test_bit + "  test_sampling_rate = " + test_sampling_rate
                    + "   test_channel_config = " + test_channel_config);
                    final PCMEncoder pcmEncoder = new PCMEncoder(test_bit, test_sampling_rate, 1);
                    pcmEncoder.setOutputPath(outputPath);
                    pcmEncoder.prepare();
                    for (int i = 0; i < numFiles; i++) {
                        Log.d(TAG, "Encoding: " + i);
//                        InputStream inputStream = getAssets().open("audio_test.pcm");
//                        inputStream.skip(44);  .wav
                        pcmEncoder.encode(inputStream, test_sampling_rate);
                    }
                    pcmEncoder.stop();
                    if (mM4aEncoderCallback != null) {
                        mM4aEncoderCallback.onStop();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Cannot create FileInputStream", e);
                    if (mM4aEncoderCallback != null) {
                        mM4aEncoderCallback.onFailed();
                    }
                }
            }
        };
    }

    private M4aEncoderCallback mM4aEncoderCallback;
    public void setM4aEncoderCallback(M4aEncoderCallback callback) {
        mM4aEncoderCallback = callback;
    }
    public interface M4aEncoderCallback{

        void onStart();
        void onStop();

        void onFailed();
    }
}
