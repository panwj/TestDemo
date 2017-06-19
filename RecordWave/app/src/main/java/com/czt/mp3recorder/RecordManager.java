package com.czt.mp3recorder;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import utils.FileUtils;
import utils.SharedPreferencesUtil;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Created by panwenjuan on 17-5-2.
 */
public class RecordManager {
    private static final String TAG = "RecordManager";
    private static volatile RecordManager mInstance;

    private MP3MediaRecorder mp3MediaRecorder;
    private MP3AudioRecorder mp3AudioRecorder;
    private AudioRecorderPcm mAudioRecorderPcm;

    private Context mContext;
    private String mFilePath;
    private int mRecordMode = 0;// 0: mediarecord  1:audiorecord

    private RecordManager(Context context) {
        mContext = context;
        mRecordMode = (int) SharedPreferencesUtil.get(mContext, "record_mode", 0);
    };

    public static RecordManager getInstance(Context context) {
        if (mInstance == null) {
            synchronized (RecordManager.class) {
                if (mInstance == null) {
                    mInstance = new RecordManager(context);
                }
            }
        }
        return mInstance;
    }

    public void startRecord() {
        mRecordMode = (int) SharedPreferencesUtil.get(mContext, "record_mode", 0);
        if (mRecordMode == 0) {
            Log.d(TAG, "startRecord() ---> mediaRecord");
            mediaRecordStart();
        } else if (mRecordMode == 1) {
            Log.d(TAG, "startRecord() ---> audioRecord");
            resolveRecord();
        } else if (mRecordMode == 2) {
            Log.d(TAG, "startRecord() ---> audioRecord pcm");
            resolveRecordPcm();
        }
    }

    public void stopRecord() {
//        mRecordMode = (int) SharedPreferencesUtil.get(mContext, "record_mode", 0);
        if (mRecordMode == 0) {
            Log.d(TAG, "stopRecord() ---> mediaRecord");
            mediaRecordStop();
        } else if (mRecordMode == 1) {
            Log.d(TAG, "stopRecord() ---> audioRecord");
            resolveStopRecord();
        } else if (mRecordMode == 2) {
            Log.d(TAG, "stopRecord() ---> audioRecord  pcm");
            resolveStopRecordPcm();
        }
        mInstance = null;
    }

    public String getFilePath() {
        return mFilePath;
    }

    /**
     * audio start
     */
    private void resolveRecord() {
        Log.d(TAG, "resolveRecord()");
        mFilePath = FileUtils.getAppPath();
        File file = new File(mFilePath);
        if (!file.exists()) {
            if (!file.mkdirs()) {
                Toast.makeText(mContext, "audio create file error", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        mFilePath = FileUtils.getAppPath() + UUID.randomUUID().toString() + ".mp3";
        Log.d(TAG, "filePath = " + mFilePath);
        mp3AudioRecorder = new MP3AudioRecorder(mContext, new File(mFilePath));
//        int size = getScreenWidth(getActivity()) / dip2px(getActivity(), 1);//控件默认的间隔是1
//        mRecorder.setDataList(audioWave.getRecList(), size);
        mp3AudioRecorder.setErrorHandler(new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (msg.what == MP3AudioRecorder.ERROR_TYPE) {
                    Toast.makeText(mContext, "audio recording error", Toast.LENGTH_SHORT).show();
                    resolveError();
                }
            }
        });

        //audioWave.setBaseRecorder(mRecorder);

        try {
            mp3AudioRecorder.start();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(mContext, "audio recording error", Toast.LENGTH_SHORT).show();
            resolveError();
            return;
        }
    }

    /**
     * audio stop
     */
    private void resolveStopRecord() {
        Log.d(TAG, "resolveStopRecord()");
        if (mp3AudioRecorder != null && mp3AudioRecorder.isRecording()) {
            mp3AudioRecorder.setPause(false);
            mp3AudioRecorder.stop();
        }
    }

    //--------------------------  audio pcm enter

    /**
     * PCM audio start
     */
    private void resolveRecordPcm() {
        Log.d(TAG, "resolveRecordPcm()");
        mFilePath = FileUtils.getAppPath();
        File file = new File(mFilePath);
        if (!file.exists()) {
            if (!file.mkdirs()) {
                Toast.makeText(mContext, "audio create file error", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        mFilePath = FileUtils.getAppPath() + UUID.randomUUID().toString() + ".pcm";
        Log.d(TAG, "filePath = " + mFilePath);
        mAudioRecorderPcm = new AudioRecorderPcm(mContext, new File(mFilePath));
//        int size = getScreenWidth(getActivity()) / dip2px(getActivity(), 1);//控件默认的间隔是1
//        mRecorder.setDataList(audioWave.getRecList(), size);

        //audioWave.setBaseRecorder(mRecorder);

        try {
            mAudioRecorderPcm.start();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(mContext, "audio recording error", Toast.LENGTH_SHORT).show();
            resolveError();
            return;
        }
    }

    /**
     *  PCM audio stop
     */
    private void resolveStopRecordPcm() {
        Log.d(TAG, "resolveStopRecordPcm()  pcm");
        if (mAudioRecorderPcm != null && mAudioRecorderPcm.isRecording()) {
            mAudioRecorderPcm.stop();
        }
    }

    //--------------------------  audio pcm exit

    //--------------------------  android.media record

    /**
     * android.media start
     */
    private void mediaRecordStart() {
        Log.d(TAG, "mediaRecordStart()");
        mFilePath = FileUtils.getAppPath();
        File file = new File(mFilePath);
        if (!file.exists()) {
            if (!file.mkdirs()) {
                Toast.makeText(mContext, "create file error", Toast.LENGTH_SHORT).show();
                return;
            }
        }
//        filePath = FileUtils.getAppPath() + UUID.randomUUID().toString() + ".mp3";
        mp3MediaRecorder = new MP3MediaRecorder(mContext, new File(mFilePath));
        mp3MediaRecorder.setErrorHandler(new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (msg.what == MP3MediaRecorder.ERROR_TYPE) {
                    Log.d(TAG, "setErrorHandler() ---> recording error");
                    Toast.makeText(mContext, "android.media recording error", Toast.LENGTH_SHORT).show();
                    resolveError();
                }
            }
        });
        try {
            mp3MediaRecorder.start();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            mFilePath = mp3MediaRecorder.getFilePath();
            Log.d(TAG, "mediaRecordStart filePath = " + mFilePath);
        }
    }

    /**
     * android.media stop
     */
    private void mediaRecordStop() {
        Log.d(TAG, "mediaRecordStop()");
        if (mp3MediaRecorder != null && mp3MediaRecorder.isRecording()) {
            mp3MediaRecorder.stop();
            mp3MediaRecorder = null;
        }
    }

    /**
     * recording failed
     */
    private void resolveError() {
        Log.d(TAG, "resolveError()");
        FileUtils.deleteFile(mFilePath);
        mFilePath = "";
        if (mp3AudioRecorder != null && mp3AudioRecorder.isRecording()) {
            mp3AudioRecorder.stop();
            mp3AudioRecorder = null;
        }
        mediaRecordStop();
    }
}
