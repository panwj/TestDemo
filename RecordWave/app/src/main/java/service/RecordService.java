package service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import com.czt.mp3recorder.MP3MediaRecorder;
import com.czt.mp3recorder.MP3AudioRecorder;
import com.czt.mp3recorder.MP3RecordManager;
import com.shuyu.app.PlayActivity;
import com.shuyu.waveview.FileUtils;
import com.shuyu.waveview.SharedPreferencesUtil;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Created by panwenjuan on 17-4-25.
 */
public class RecordService extends Service {
    private static final String TAG = "RecordService";
    private TelephonyManager mTelephonyManager = null;
    private OutgoingCallPhone mOutgoingCallPhone = null;
    private MP3RecordManager mp3RecordManager;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "RecordService ----> onCreate()");
        mTelephonyManager = (TelephonyManager)getSystemService(TELEPHONY_SERVICE);
        mTelephonyManager.listen(new PhonyState(), PhoneStateListener.LISTEN_CALL_STATE);
        registerOutgoingBroadcastReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        mTelephonyManager = null;
        mp3RecordManager = null;
        unregisterOutgoingBroadcastReceiver();
        super.onDestroy();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
    }


    class PhonyState extends PhoneStateListener{
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            super.onCallStateChanged(state, incomingNumber);

            switch (state){
                case TelephonyManager.CALL_STATE_IDLE:
                    Log.d(TAG, "CALL_STATE_IDLE");
                    if (mp3RecordManager != null) {
                        mp3RecordManager.stopRecord();
                        Intent intent = new Intent();
                        intent.setClass(RecordService.this, PlayActivity.class);
                        intent.putExtra("file_path", mp3RecordManager.getFilePath());
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                        startActivity(intent);
                        mp3RecordManager = null;
                    }
                    break;
                case TelephonyManager.CALL_STATE_RINGING:
                    Log.d(TAG, "CALL_STATE_RINGING");
                    break;
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    Log.d(TAG, "CALL_STATE_OFFHOOK");

                    mp3RecordManager = MP3RecordManager.getInstance(RecordService.this);
                    if (mp3RecordManager != null) {
                        mp3RecordManager.startRecord();
                    }
                    break;
            }
        }
    }

    private void registerOutgoingBroadcastReceiver(){
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_NEW_OUTGOING_CALL);
        registerReceiver(getInstanceOutgoingCallPhone(), filter);
    }
    private void unregisterOutgoingBroadcastReceiver(){
        mOutgoingCallPhone = null;
    }

    private OutgoingCallPhone getInstanceOutgoingCallPhone(){
        if (mOutgoingCallPhone == null){
            mOutgoingCallPhone = new OutgoingCallPhone();
        }
        return mOutgoingCallPhone;
    }

    class OutgoingCallPhone extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getAction().equals(Intent.ACTION_NEW_OUTGOING_CALL)) {

            }
        }
    }
}
