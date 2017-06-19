package com;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.view.test.R;

import java.io.File;
import java.text.DecimalFormat;

/**
 * Created by panwenjuan on 17-4-26.
 */
public class PlayActivity extends Activity implements View.OnClickListener{

    AudioPlayer audioPlayer;
    String filePath = "";
    boolean mIsPlay = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.popup_layout);

        Intent intent = getIntent();
        if (intent != null) {
            filePath = intent.getStringExtra("file_path");
        }
        Log.d("zzz", "pop filePath : " + filePath);
        String fileSize = "";
        if (!TextUtils.isEmpty(filePath)) {
            File file = new File(filePath);
            if (file != null && file.exists()) {
                fileSize = getFileSize(file.length());
            }
        }
        TextView textView = (TextView) findViewById(R.id.text);
        textView.setText(filePath + "size : " + fileSize);
        findViewById(R.id.play).setOnClickListener(this);
        findViewById(R.id.play2).setOnClickListener(this);

        audioPlayer = new AudioPlayer(this, new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case AudioPlayer.HANDLER_CUR_TIME://更新的时间

                        break;
                    case AudioPlayer.HANDLER_COMPLETE://播放结束
                        mIsPlay = false;
                        break;
                    case AudioPlayer.HANDLER_PREPARED://播放开始

                        break;
                    case AudioPlayer.HANDLER_ERROR://播放错误
                        resolveResetPlay();
                        break;
                }

            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mIsPlay) {
            audioPlayer.pause();
            audioPlayer.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.play:
                resolvePlayRecord();
                break;
            case R.id.play2:
                if (TextUtils.isEmpty(filePath) || !new File(filePath).exists()) {
                    Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
                    return;
                }
                AudioTrackManager.getInstance(getApplicationContext()).startPlay(filePath);
//                new AudioTrackManager().startPlay("/storage/emulated/0/appRecorderTest/59a1a5eb-8655-4f75-a01d-3ffc7ef64dce.pcm");
                break;
        }
    }

    private void resolvePlayRecord() {
        if (TextUtils.isEmpty(filePath) || !new File(filePath).exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        mIsPlay = true;
        audioPlayer.playUrl(filePath);
    }

    private void resolveResetPlay() {
        filePath = "";
        if (mIsPlay) {
            mIsPlay = false;
            audioPlayer.pause();
        }
    }

    private String getFileSize(long size) {
        DecimalFormat df = new DecimalFormat("#.00");
        String fileSizeString = "";
        String wrongSize = "0B";
        if (size == 0) {
            return wrongSize;
        }
        if (size < 1024) {
            fileSizeString = df.format((double) size) + "B";
        } else if (size < 1048576) {
            fileSizeString = df.format((double) size / 1024) + "KB";
        } else if (size < 1073741824) {
            fileSizeString = df.format((double) size / 1048576) + "MB";
        } else {
            fileSizeString = df.format((double) size / 1073741824) + "GB";
        }
        return fileSizeString;
    }
}
