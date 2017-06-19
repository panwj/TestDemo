package com.czt.mp3recorder;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import com.BaseRecorder;
import utils.FileUtils;
import utils.RecordHelper;
import utils.SharedPreferencesUtil;
import utils.Utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public class MP3MediaRecorder extends BaseRecorder {
    //=======================AudioRecord Default Settings=======================
    private static final int DEFAULT_MEDIA_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final int DEFAULT_MEDIA_FORMAT = MediaRecorder.OutputFormat.THREE_GPP;
    private static final int DEFAULT_MEDIA_ENCODE = MediaRecorder.AudioEncoder.AMR_NB;
    /**
     * 以下三项为默认配置参数。Google Android文档明确表明只有以下3个参数是可以在所有设备上保证支持的。
     */
    private static final int DEFAULT_SAMPLING_RATE = 44100;//模拟器仅支持从麦克风输入8kHz采样率
    private static final int DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int DEFAULT_ENCODING_BIT_RATE = AudioFormat.ENCODING_PCM_16BIT;

    /**
     * 自定义 每160帧作为一个周期，通知一下需要进行编码
     */
    private static final int FRAME_COUNT = 160;
    public static final int ERROR_TYPE = 22;


    private static String TAG = "MP3MediaRecorder";
    private Context mContext;
    private MediaRecorder mMediaRecorder = null;
    private File mRecordFile;
    private ArrayList<Short> dataList;
    private Handler errorHandler;

    private String current_file_format;
    private int current_record_source;
    private int current_record_format;
    private int device_default_support_source;
    private int device_default_support_format;


    private boolean mIsRecording = false;
    private int mMaxSize;
    private boolean mSendError;
    private boolean mPause;
    private boolean isFailRecording = false;
    private String mSaveDir;
    private String mFileUrl;

    /**
     * Default constructor. Setup recorder with default sampling rate 1 channel,
     * 16 bits pcm
     *
     * @param recordFile target file
     */
    public MP3MediaRecorder(Context context, File recordFile) {
        mContext = context;
        mRecordFile = recordFile;
    }

    /**
     * Start recording. Create an encoding thread. Start record from this
     * thread.
     *
     * @throws IOException initAudioRecorder throws
     */
    public void start() throws IOException {
        if (mIsRecording) {
            return;
        }

        try {
            initAudioRecorder();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void stop() {
        releaseMediaRecorder();
    }

    public boolean isRecording() {
        return mIsRecording;
    }

    /**
     * Initialize audio recorder
     */
    private void initAudioRecorder() throws IOException {
        initResourse();
        prepareMedia(current_record_source, current_record_format, current_file_format);
        startRecorder();
    }

    private void prepareMedia(int recordSource, int outputFormat,  String fileFormat){
        Log.d(TAG,"prepareMedia() ---> Enter");
        if (mIsRecording){
            return;
        }

        if (!FileUtils.isSdcardExit()){
            return;
        }

        isFailRecording = false;
        mSaveDir = FileUtils.getAppPath();
        new File(mSaveDir).mkdirs();
        mFileUrl = mSaveDir + Utils.getCurrentDate().replace(" ", "").replace("-", "").replace(":","") + fileFormat;

        Log.d(TAG,"mFileUrl = "+mFileUrl);

        if (mMediaRecorder != null) {
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }

        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
            @Override
            public void onError(MediaRecorder mr, int what, int extra) {
                switch (what){
                    case MediaRecorder.MEDIA_ERROR_SERVER_DIED:
                    case MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN:
                        releaseMediaRecorder();
                        errorHandler.sendEmptyMessage(ERROR_TYPE);
                        break;
                }

            }
        });

        try {
            RecordHelper.prepareAudioSetting(mContext);

            int test_source = (int) SharedPreferencesUtil.get(mContext, "source", recordSource);
            int test_format = (int) SharedPreferencesUtil.get(mContext, "format", outputFormat);
            int test_encode = (int) SharedPreferencesUtil.get(mContext, "encode", MediaRecorder.AudioEncoder.AMR_NB);
            int test_hz = (int) SharedPreferencesUtil.get(mContext, "hz", 44100);
            int test_channel = (int) SharedPreferencesUtil.get(mContext, "channel", AudioFormat.CHANNEL_IN_MONO);

            Log.d(TAG, "test_source = " + test_source);
            Log.d(TAG, "test_format = " + test_format);
            Log.d(TAG, "test_encode = " + test_encode);
            Log.d(TAG, "test_hz = " + test_hz);
            Log.d(TAG, "test_channel = " + test_channel);

            Log.d(TAG, " AudioSource : " + recordSource);
            Log.d(TAG, " AudioFormat : " + outputFormat);

            mMediaRecorder.setAudioSource(test_source);
            mMediaRecorder.setAudioSamplingRate(test_hz);
            mMediaRecorder.setAudioEncodingBitRate(AudioFormat.ENCODING_PCM_16BIT);
//            mMediaRecorder.setAudioChannels(test_channel);
            mMediaRecorder.setOutputFormat(test_format);
            mMediaRecorder.setAudioEncoder(test_encode);
            mMediaRecorder.setOutputFile(mFileUrl);

            mMediaRecorder.prepare();
        } catch (Exception e) {
            isFailRecording = true;
            Log.d(TAG, "preferMedia() exception : " + e.toString());
            errorHandler.sendEmptyMessage(ERROR_TYPE);

        }
        Log.d(TAG,"preferMedia() ---> Exit");
    }

    private void startRecorder(){
        Log.d(TAG,"startRecorder() ---> Enter");

        if (mMediaRecorder != null) {
            if (!mIsRecording){
                try {
                    mMediaRecorder.start();
                    mIsRecording = true; // 提早，防止init或startRecording被多次调用
                }catch (Exception e){

                    isFailRecording = true;
                    errorHandler.sendEmptyMessage(ERROR_TYPE);
                    Log.d(TAG,"startRecorder() fail : " + e + "    isFailRecording = " + isFailRecording);
                    /*Log.d(TAG,"startRecorder() fail : " + e + "    isFailRecording = " + isFailRecording);

                    if (mMediaRecorder != null){
                        mMediaRecorder.reset();
                        mMediaRecorder.release();
                        mMediaRecorder = null;
                        isFailRecording = false;
                        mMediaRecorder = new android.android.media.MediaRecorder();
                        try {
                            Log.d(TAG,"reset to device default source ");
                            Log.d(TAG, " AudioSource : " + device_default_support_source);
                            Log.d(TAG, " AudioFormat : " + device_default_support_format);
                            mMediaRecorder.setAudioSource(device_default_support_source);
                            mMediaRecorder.setOutputFormat(device_default_support_format);
                            mMediaRecorder.setAudioEncoder(android.android.media.MediaRecorder.AudioEncoder.AMR_NB);
                            mMediaRecorder.setOutputFile(mFileUrl);

                            mMediaRecorder.prepare();
                            mMediaRecorder.start();
                        } catch (Exception r) {
                            isFailRecording = true;
                            errorHandler.sendEmptyMessage(ERROR_TYPE);
                            Log.d(TAG, "startRecorder() fail again: " + r.toString() + "   isFailRecording = " + isFailRecording);
                        }
                    }*/
                }finally {
                    mIsRecording = true;
                    Log.d(TAG, "startRecorder() mIsRecording = "+mIsRecording);
                }

            }
        }
        Log.d(TAG,"startRecorder() ---> Exit");
    }

    private void releaseMediaRecorder(){
        Log.d(TAG,"releaseMediaRecorder() ---> Enter");

        if (mMediaRecorder != null){
            try {
                mMediaRecorder.release();
            }catch (Exception e){
                Log.d(TAG,"releaseMediaRecorder()  :"+e.getMessage());
                File file = new File(mFileUrl);
                if (file.exists()){
                    file.delete();
                }
                isFailRecording = true;
                errorHandler.sendEmptyMessage(ERROR_TYPE);
            }finally {
                mMediaRecorder = null;
                mIsRecording = false;
                mPause = false;
                RecordHelper.restoreAudioSetting(mContext);
            }
        }
        Log.d(TAG,"releaseMediaRecorder() ---> Exit");
    }

    private void initResourse() {
        int defaultSource;
        int defaultFormat;
        if (Build.VERSION.SDK_INT > 22) {
            defaultFormat = MediaRecorder.OutputFormat.MPEG_4;
            defaultSource = MediaRecorder.AudioSource.MIC;
        } else {
            defaultFormat = MediaRecorder.OutputFormat.THREE_GPP;
            defaultSource = MediaRecorder.AudioSource.VOICE_CALL;
        }

        device_default_support_format = (int) SharedPreferencesUtil.get(mContext,
                Utils.DEVICE_DEFAULT_SUPPORT_RECORDER_FORMAT, defaultFormat);
        device_default_support_source = (int) SharedPreferencesUtil.get(mContext,
                Utils.DEVICE_DEFAULT_SUPPORT_RECORDER_SOURCE, defaultSource);

        current_record_source = (int) SharedPreferencesUtil.get(mContext,
                Utils.CURRENT_USE_SOURCE, defaultSource);
        current_record_format = (int) SharedPreferencesUtil.get(mContext,
                Utils.CURRENT_USE_FORMAT, defaultFormat);

        current_file_format = Utils.getCurrentFileFormat(current_record_format);
    }

    /**
     * 获取真实的音量。 [算法来自三星]
     *
     * @return 真实音量
     */
    @Override
    public int getRealVolume() {
        return mVolume;
    }

    /**
     * 获取相对音量。 超过最大值时取最大值。
     *
     * @return 音量
     */
    public int getVolume() {
        if (mVolume >= MAX_VOLUME) {
            return MAX_VOLUME;
        }
        return mVolume;
    }

    private static final int MAX_VOLUME = 2000;

    /**
     * 根据资料假定的最大值。 实测时有时超过此值。
     *
     * @return 最大音量值。
     */
    public int getMaxVolume() {
        return MAX_VOLUME;
    }

    public boolean isPause() {
        return mPause;
    }

    /**
     * 是否暂停
     */
    public void setPause(boolean pause) {
        this.mPause = pause;
    }

    /**
     * 设置错误回调
     *
     * @param errorHandler 错误通知
     */
    public void setErrorHandler(Handler errorHandler) {
        this.errorHandler = errorHandler;
    }


    public void deleteFile(String filePath) {
        File file = new File(filePath);
        if (file.exists()) {
            if (file.isFile()) {
                file.delete();
            } else {
                String[] filePaths = file.list();
                for (String path : filePaths) {
                    deleteFile(filePath + File.separator + path);
                }
                file.delete();
            }
        }
    }

    public String getFilePath() {
        return mFileUrl;
    }

}