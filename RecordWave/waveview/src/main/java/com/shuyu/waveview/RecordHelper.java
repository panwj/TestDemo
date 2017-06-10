package com.shuyu.waveview;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import com.shuyu.waveview.entity.PhoneInfo;

import java.util.ArrayList;
import java.util.List;


/**
 * Created by mengzhao on 16/7/21.
 */
public class RecordHelper {

    private static final boolean DBG = BuildConfig.DEBUG;
    private static final String TAG = "RecordHelper";

    private static List<PhoneInfo> sDeviceConfigList;

    static {
        initDeviceRecordConfig();
    }


    public static List<PhoneInfo> getDeviceRecordConfig(){
        return sDeviceConfigList;
    }


    private static boolean shouldChangeAudioSetting(){
        String deviceName = DeviceName.getDeviceName();
        return (deviceName.toLowerCase().contains("htc")) && (Build.VERSION.SDK_INT >= 21);
    }


    public static void prepareAudioSetting(Context ctx){

        if(shouldChangeAudioSetting()){
            Log.d(TAG, "prepare specific audio setting");
            AudioManager localAudioManager = (AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
            localAudioManager.setParameters("INCALL_RECORDING_MODE=ON");
            localAudioManager.setParameters("VOICE_RECORDING_MODE=ON");
        }
    }

    public static void restoreAudioSetting(Context ctx){

        if(shouldChangeAudioSetting()){
            Log.d(TAG, "restore audio setting");
            AudioManager localAudioManager = (AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
            localAudioManager.setParameters("INCALL_RECORDING_MODE=OFF");
            localAudioManager.setParameters("VOICE_RECORDING_MODE=OFF");
        }
    }

    public static void setDefaultRecordConfig(Context ctx) {
        int sdk_int = Build.VERSION.SDK_INT;
        String release = Build.VERSION.RELEASE;
        String model = Build.MODEL;
        String manufacturer = Build.MANUFACTURER;

        String deviceName = DeviceName.getDeviceName();

        Log.d(TAG, "====================================");
        Log.d(TAG, "deviceName : " + deviceName);
        Log.d(TAG, "device model : " + model);
        Log.d(TAG, "device release : " + release);
        Log.d(TAG, "device manufacturer : " + manufacturer);
        Log.d(TAG, "SDK INT : " + sdk_int);
        Log.d(TAG, "====================================");

        PhoneInfo phoneInfo = new PhoneInfo();
        phoneInfo.setmRelease(release);
        phoneInfo.setDeviceName(deviceName);
        phoneInfo.setmSdk_int(sdk_int);

        if (sdk_int <= 22) {
            phoneInfo.setmAudioSource(MediaRecorder.AudioSource.VOICE_CALL);
            phoneInfo.setmAudioFormat(MediaRecorder.OutputFormat.THREE_GPP);
        } else {
            phoneInfo.setmAudioSource(MediaRecorder.AudioSource.MIC);
            phoneInfo.setmAudioFormat(MediaRecorder.OutputFormat.MPEG_4);
        }

        for (int i = 0; i < sDeviceConfigList.size(); i++) {
            PhoneInfo p = sDeviceConfigList.get(i);
            if (!TextUtils.isEmpty(p.mDeviceName) && phoneInfo.mDeviceName.contains(p.mDeviceName) && (phoneInfo.mSdk_int == p.mSdk_int)) {

                phoneInfo.mAudioFormat = p.mAudioFormat;
                phoneInfo.mAudioSource = p.mAudioSource;

                if(DBG) Log.d(TAG, "device name : " + phoneInfo.mDeviceName);
                if(DBG) Log.d(TAG, "config name : " + p.mDeviceName);
                if(DBG) Log.d(TAG, "SET AudioSouce : " + phoneInfo.mAudioSource);
                if(DBG) Log.d(TAG, "SDK AudioFormat : " + phoneInfo.mAudioFormat);

                Bundle bundle = new Bundle();
                bundle.putString("DEVICE_NAME", deviceName);
                bundle.putString("DEVICE_MODEL", Build.MODEL);
                bundle.putInt("SDK_INT", Build.VERSION.SDK_INT);
                bundle.putInt("AudioSouce", phoneInfo.mAudioSource);
                bundle.putInt("AudioFormat", phoneInfo.mAudioFormat);
                break;
            }
        }

        // init value
        SharedPreferencesUtil.put(ctx, Utils.CURRENT_USE_SOURCE, phoneInfo.getmAudioSource());
        SharedPreferencesUtil.put(ctx, Utils.CURRENT_USE_FORMAT, phoneInfo.getmAudioFormat());

        // default value
        if (sdk_int <= 22) {
            SharedPreferencesUtil.put(ctx, Utils.DEVICE_DEFAULT_SUPPORT_RECORDER_SOURCE, MediaRecorder.AudioSource.VOICE_CALL);
            SharedPreferencesUtil.put(ctx, Utils.DEVICE_DEFAULT_SUPPORT_RECORDER_FORMAT, MediaRecorder.OutputFormat.THREE_GPP);
        } else {
            SharedPreferencesUtil.put(ctx, Utils.DEVICE_DEFAULT_SUPPORT_RECORDER_SOURCE, MediaRecorder.AudioSource.MIC);
            SharedPreferencesUtil.put(ctx, Utils.DEVICE_DEFAULT_SUPPORT_RECORDER_FORMAT, MediaRecorder.OutputFormat.MPEG_4);
        }

    }



    // ===========================================
    // maintain this list for adapt each device.
    // ===========================================
    private static void initDeviceRecordConfig(){
        sDeviceConfigList = new ArrayList<PhoneInfo>();
        sDeviceConfigList.add(new PhoneInfo("4", "", 21, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5", "", 22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6", "", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "CyanogenMod", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("5", "Asus", 22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5", "BlackBerry", 22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "BlackBerry", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "BlackBerry PRIV", 23, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("5", "BQ Aquaris", 22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6", "BQ Aquaris", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));

        sDeviceConfigList.add(new PhoneInfo("4", "Huawei", 21, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5", "Huawei", 22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6", "Huawei", 23, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "Huawei P9", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("5", "Lenovo", 22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6", "Lenovo", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("4","LG",21, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5","LG",22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6","LG",23, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("4","Micromax Canvas",21, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5","Micromax Canvas",22, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6","Micromax Canvas",23, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("4","Moto",21, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5","Moto",22, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.AMR_NB));
        sDeviceConfigList.add(new PhoneInfo("6","Moto",23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.AMR_NB));
        sDeviceConfigList.add(new PhoneInfo("5","Nexus",22, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6","Nexus",23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("5","One Plus One",22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5","One Plus Two",22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6","One Plus One",23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6","One Plus Two",23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("4", "Samsung", 21, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5", "Samsung", 22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6", "Samsung S6", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "Samsung S7", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "Samsung N5", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "Samsung USA S6", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "Samsung USA S7", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "Samsung USA N5", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "Samsung S5", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "Samsung N4", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("6", "Samsung Edge", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("4", "Sony", 21, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5", "Sony", 22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6", "Sony", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("5", "Wiko", 22, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6", "Wiko", 23, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5", "Wileyfox", 22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6", "Wileyfox", 23, MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4));
        sDeviceConfigList.add(new PhoneInfo("5", "ZTE", 22, MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.OutputFormat.THREE_GPP));

        // Already Adapted device
        sDeviceConfigList.add(new PhoneInfo("5", "HTC", 21, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5.1", "HTC", 22, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6", "HTC", 23, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));

        sDeviceConfigList.add(new PhoneInfo("5", "Redmi Note", 21, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("5.1", "Redmi Note", 22, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));
        sDeviceConfigList.add(new PhoneInfo("6", "Redmi Note", 23, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.OutputFormat.THREE_GPP));

    }
}
