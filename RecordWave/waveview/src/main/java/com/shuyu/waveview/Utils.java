package com.shuyu.waveview;

import android.media.MediaRecorder;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by panwenjuan on 17-4-26.
 */
public class Utils {
    public static String CURRENT_USE_SOURCE = "current_record_source";

    public static String CURRENT_USE_FORMAT = "current_record_format";

    public static final String DEVICE_DEFAULT_SUPPORT_RECORDER_SOURCE = "device_default_support_recorder_source";
    public static final String DEVICE_DEFAULT_SUPPORT_RECORDER_FORMAT = "device_default_support_recorder_format";

    public static String getCurrentDate() {
        String currentDate = "";
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        currentDate = sdf.format(date).trim();
        return currentDate;
    }

    public static String  getCurrentFileFormat(int recordFormat){
        String current_file_format = ".amr";
        switch (recordFormat){
            case MediaRecorder.OutputFormat.AMR_NB:
                current_file_format = ".amr";
                break;
            case MediaRecorder.OutputFormat.THREE_GPP:
                current_file_format = ".3gp";
                break;
            case MediaRecorder.OutputFormat.MPEG_4:
                current_file_format = ".mp4";
                break;
        }
        return current_file_format;
    }

    public static String getFormat(int format) {
        String audio_format = "AMR_NB";
        switch (format){
            case MediaRecorder.OutputFormat.AMR_NB:
                audio_format = "AMR_NB";
                break;
            case MediaRecorder.OutputFormat.THREE_GPP:
                audio_format = "THREE_GPP";
                break;
            case MediaRecorder.OutputFormat.MPEG_4:
                audio_format = "MPEG_4";
                break;
        }
        return audio_format;
    }

    public static String getSource(int source) {
        String audio_source = "VOICE_CALL";
        switch (source) {
            case MediaRecorder.AudioSource.MIC:
                audio_source = "MIC";
                break;
            case MediaRecorder.AudioSource.VOICE_CALL:
                audio_source = "VOICE_CALL";
                break;
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION:
                audio_source = "VOICE_COMMUNICATION";
                break;
            case MediaRecorder.AudioSource.VOICE_UPLINK:
                audio_source = "VOICE_UPLINK";
                break;
            case MediaRecorder.AudioSource.VOICE_DOWNLINK:
                audio_source = "VOICE_DOWNLINK";
                break;
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                audio_source = "VOICE_RECOGNITION";
                break;
        }
        return audio_source;
    }
}
