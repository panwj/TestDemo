package com;

import android.content.Intent;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.encoder.amr.AmrEncoder;
import com.view.test.R;

import java.io.IOException;
import java.io.InputStream;

import utils.SharedPreferencesUtil;

import service.RecordService;

/**
 * Created by shuyu on 2016/11/15.
 * 声音波形，录制与播放
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener{


   /* @BindView(R.id.main_frameLayout)
    FrameLayout mainFrameLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        MainFragment newFragment = new MainFragment();
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.main_frameLayout, newFragment);
        transaction.addToBackStack(null);
        transaction.commit();

        Intent intent = new Intent();
        intent.setClass(this, RecordService.class);
        startService(intent);
    }*/

    private TextView source_1, source_2, source_3, source_4, source_5, source_6, source_7;
    private TextView encorder_1, encorder_2, encorder_3, encorder_4;
    private TextView format_1, format_2, format_3, format_4, format_5, format_6;
    private TextView hz_0, hz_1, hz_2, hz_3, hz_4, hz_5, hz_6;
    private TextView encording_1, encording_2, encording_3, encording_4,encording_5, encording_6, encording_7, encording_8, encording_9;
    private TextView channel_1, channel_2, channel_3, channel_4, channel_5, channel_6, channel_7, channel_8, channel_9;
    private TextView record_mode_0, record_mode_1, record_mode_2;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.setting_layout);
        int source1 = MediaRecorder.AudioSource.DEFAULT;
        int source2 = MediaRecorder.AudioSource.MIC;
        int source3 = MediaRecorder.AudioSource.VOICE_CALL;
        int source4 = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        int source5 = MediaRecorder.AudioSource.VOICE_DOWNLINK;
        int source6 = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        int source7 = MediaRecorder.AudioSource.VOICE_UPLINK;

        int encoder1 = MediaRecorder.AudioEncoder.AAC;
        int encoder2 = MediaRecorder.AudioEncoder.AMR_NB;
        int encoder3 = MediaRecorder.AudioEncoder.AMR_WB;
        int encoder4 = MediaRecorder.AudioEncoder.DEFAULT;

        int format1 = MediaRecorder.OutputFormat.AAC_ADTS;//.aac
        int format2 = MediaRecorder.OutputFormat.AMR_NB;//.amr
        int format3 = MediaRecorder.OutputFormat.AMR_WB;//.amr
        int format4 = MediaRecorder.OutputFormat.MPEG_4;//.mp4
        int format5 = MediaRecorder.OutputFormat.THREE_GPP;//.3gp
        int format6 = MediaRecorder.OutputFormat.DEFAULT;//.amr

        // audio -------------------
        int encoding1 = AudioFormat.ENCODING_DEFAULT;
        int encoding2 = AudioFormat.ENCODING_PCM_8BIT;
        int encoding3 = AudioFormat.ENCODING_PCM_16BIT;
//        int encoding4 = AudioFormat.ENCODING_PCM_FLOAT;
//        int encoding5 = AudioFormat.ENCODING_DTS;
//        int encoding6 = AudioFormat.ENCODING_DTS_HD;
        int encoding7 = AudioFormat.ENCODING_INVALID;
//        int encoding8 = AudioFormat.ENCODING_AC3;
//        int encoding9 = AudioFormat.ENCODING_E_AC3;

        int channel1 = AudioFormat.CHANNEL_IN_MONO;
        int channel2 = AudioFormat.CHANNEL_IN_STEREO;
        int channel3 = AudioFormat.CHANNEL_IN_DEFAULT;
        int channel4 = AudioFormat.CHANNEL_INVALID;
        int channel5 = AudioFormat.CHANNEL_IN_FRONT;
        int channel6 = AudioFormat.CHANNEL_IN_BACK;
        int channel7 = AudioFormat.CHANNEL_IN_PRESSURE;
        int channel8 = AudioFormat.CHANNEL_IN_VOICE_DNLINK;
        int channel9 = AudioFormat.CHANNEL_IN_VOICE_UPLINK;

        source_1 = (TextView) findViewById(R.id.source_1);
        source_2 = (TextView) findViewById(R.id.source_2);
        source_3 = (TextView) findViewById(R.id.source_3);
        source_4 = (TextView) findViewById(R.id.source_4);
        source_5 = (TextView) findViewById(R.id.source_5);
        source_6 = (TextView) findViewById(R.id.source_6);
        source_7 = (TextView) findViewById(R.id.source_7);
        source_1.setOnClickListener(this);
        source_2.setOnClickListener(this);
        source_3.setOnClickListener(this);
        source_4.setOnClickListener(this);
        source_5.setOnClickListener(this);
        source_6.setOnClickListener(this);
        source_7.setOnClickListener(this);

        encorder_1 = (TextView) findViewById(R.id.encode_1);
        encorder_2 = (TextView) findViewById(R.id.encode_2);
        encorder_3 = (TextView) findViewById(R.id.encode_3);
        encorder_4 = (TextView) findViewById(R.id.encode_4);
        encorder_1.setOnClickListener(this);
        encorder_2.setOnClickListener(this);
        encorder_3.setOnClickListener(this);
        encorder_4.setOnClickListener(this);

        format_1 = (TextView) findViewById(R.id.format_1);
        format_2 = (TextView) findViewById(R.id.format_2);
        format_3 = (TextView) findViewById(R.id.format_3);
        format_4 = (TextView) findViewById(R.id.format_4);
        format_5 = (TextView) findViewById(R.id.format_5);
        format_6 = (TextView) findViewById(R.id.format_6);
        format_1.setOnClickListener(this);
        format_2.setOnClickListener(this);
        format_3.setOnClickListener(this);
        format_4.setOnClickListener(this);
        format_5.setOnClickListener(this);
        format_6.setOnClickListener(this);

        hz_0 = (TextView) findViewById(R.id.hz_0);
        hz_1 = (TextView) findViewById(R.id.hz_1);
        hz_2 = (TextView) findViewById(R.id.hz_2);
        hz_3 = (TextView) findViewById(R.id.hz_3);
        hz_4 = (TextView) findViewById(R.id.hz_4);
        hz_5 = (TextView) findViewById(R.id.hz_5);
        hz_6 = (TextView) findViewById(R.id.hz_6);
        hz_0.setOnClickListener(this);
        hz_1.setOnClickListener(this);
        hz_2.setOnClickListener(this);
        hz_3.setOnClickListener(this);
        hz_4.setOnClickListener(this);
        hz_5.setOnClickListener(this);
        hz_6.setOnClickListener(this);

        encording_1 = (TextView) findViewById(R.id.encoding_1);
        encording_2 = (TextView) findViewById(R.id.encoding_2);
        encording_3 = (TextView) findViewById(R.id.encoding_3);
        encording_4 = (TextView) findViewById(R.id.encoding_4);
        encording_5 = (TextView) findViewById(R.id.encoding_5);
        encording_6 = (TextView) findViewById(R.id.encoding_6);
        encording_7 = (TextView) findViewById(R.id.encoding_7);
        encording_8 = (TextView) findViewById(R.id.encoding_8);
        encording_9 = (TextView) findViewById(R.id.encoding_9);
        encording_1.setOnClickListener(this);
        encording_2.setOnClickListener(this);
        encording_3.setOnClickListener(this);
//        encording_4.setOnClickListener(this);
//        encording_5.setOnClickListener(this);
//        encording_6.setOnClickListener(this);
        encording_7.setOnClickListener(this);
//        encording_8.setOnClickListener(this);
//        encording_9.setOnClickListener(this);

        channel_1 = (TextView) findViewById(R.id.channel_1);
        channel_2 = (TextView) findViewById(R.id.channel_2);
        channel_3 = (TextView) findViewById(R.id.channel_3);
        channel_4 = (TextView) findViewById(R.id.channel_4);
        channel_5 = (TextView) findViewById(R.id.channel_5);
        channel_6 = (TextView) findViewById(R.id.channel_6);
        channel_7 = (TextView) findViewById(R.id.channel_7);
        channel_8 = (TextView) findViewById(R.id.channel_8);
        channel_9 = (TextView) findViewById(R.id.channel_9);
        channel_1.setOnClickListener(this);
        channel_2.setOnClickListener(this);
        channel_3.setOnClickListener(this);
        channel_4.setOnClickListener(this);
        channel_5.setOnClickListener(this);
        channel_6.setOnClickListener(this);
        channel_7.setOnClickListener(this);
        channel_8.setOnClickListener(this);
        channel_9.setOnClickListener(this);

        record_mode_0 = (TextView) findViewById(R.id.record_mode_0);
        record_mode_1 = (TextView) findViewById(R.id.record_mode_1);
        record_mode_2 = (TextView) findViewById(R.id.record_mode_2);
        record_mode_0.setOnClickListener(this);
        record_mode_1.setOnClickListener(this);
        record_mode_2.setOnClickListener(this);
        showSourceUI((int) SharedPreferencesUtil.get(this, "source", -1));
        showEncorderUI((int) SharedPreferencesUtil.get(this, "encode", -1));
        showFormatUI((int) SharedPreferencesUtil.get(this, "format", -1));
        showEncordingUI((int) SharedPreferencesUtil.get(this, "encoding", -1));
        showChannelUI((int) SharedPreferencesUtil.get(this, "channel", -1));
        showHzUI((int) SharedPreferencesUtil.get(this, "hz", -1));
        int mode = (int) SharedPreferencesUtil.get(this, "record_mode", -1);
        if (mode == 0) {
            record_mode_0.setTextColor(getResources().getColor(R.color.red));
            record_mode_1.setTextColor(getResources().getColor(R.color.black));
            record_mode_2.setTextColor(getResources().getColor(R.color.black));
        } else if (mode == 1) {
            record_mode_0.setTextColor(getResources().getColor(R.color.black));
            record_mode_1.setTextColor(getResources().getColor(R.color.red));
            record_mode_2.setTextColor(getResources().getColor(R.color.black));
        } else if (mode == 2) {
            record_mode_0.setTextColor(getResources().getColor(R.color.black));
            record_mode_1.setTextColor(getResources().getColor(R.color.black));
            record_mode_2.setTextColor(getResources().getColor(R.color.red));
        } else {
            record_mode_0.setTextColor(getResources().getColor(R.color.black));
            record_mode_1.setTextColor(getResources().getColor(R.color.black));
            record_mode_2.setTextColor(getResources().getColor(R.color.black));
        }

        Intent intent = new Intent();
        intent.setClass(this, RecordService.class);
        startService(intent);
//        String path = "/storage/emulated/0/appRecorderTest/63f541af-cb92-49c9-81ee-2f37c93c95eb.pcm";
//        AmrEncoder.pcm2Amr(path, path.replace(".pcm", ".amr"));

       /* String rootPath = Environment.getExternalStorageDirectory().getPath();
        String amrPath = rootPath + "/test2.amr";
        try {
            InputStream pcmStream = getAssets().open("test2.pcm");
            AmrEncoder.pcm2Amr(pcmStream, amrPath);
        } catch (IOException e) {
            e.printStackTrace();
        }*/
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.source_1:
                setData("source", MediaRecorder.AudioSource.DEFAULT);
                setSourceUI(R.id.source_1);
                break;
            case R.id.source_2:
                setData("source", MediaRecorder.AudioSource.MIC);
                setSourceUI(R.id.source_2);
                break;
            case R.id.source_3:
                setData("source", MediaRecorder.AudioSource.VOICE_CALL);
                setSourceUI(R.id.source_3);
                break;
            case R.id.source_4:
                setData("source", MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                setSourceUI(R.id.source_4);
                break;
            case R.id.source_5:
                setData("source", MediaRecorder.AudioSource.VOICE_DOWNLINK);
                setSourceUI(R.id.source_5);
                break;
            case R.id.source_6:
                setData("source", MediaRecorder.AudioSource.VOICE_RECOGNITION);
                setSourceUI(R.id.source_6);
                break;
            case R.id.source_7:
                setData("source", MediaRecorder.AudioSource.VOICE_UPLINK);
                setSourceUI(R.id.source_7);
                break;
            case R.id.format_1:
                setData("format", MediaRecorder.OutputFormat.AAC_ADTS);
                setFormatUI(R.id.format_1);
                break;
            case R.id.format_2:
                setData("format", MediaRecorder.OutputFormat.AMR_NB);
                setFormatUI(R.id.format_2);
                break;
            case R.id.format_3:
                setData("format", MediaRecorder.OutputFormat.AMR_WB);
                setFormatUI(R.id.format_3);
                break;
            case R.id.format_4:
                setData("format", MediaRecorder.OutputFormat.MPEG_4);
                setFormatUI(R.id.format_4);
                break;
            case R.id.format_5:
                setData("format", MediaRecorder.OutputFormat.THREE_GPP);
                setFormatUI(R.id.format_5);
                break;
            case R.id.format_6:
                setData("format", MediaRecorder.OutputFormat.DEFAULT);
                setFormatUI(R.id.format_6);
                break;
            case R.id.encode_1:
                setData("encode", MediaRecorder.AudioEncoder.AAC);
                setEncorderUI(R.id.encode_1);
                break;
            case R.id.encode_2:
                setData("encode", MediaRecorder.AudioEncoder.AMR_NB);
                setEncorderUI(R.id.encode_2);
                break;
            case R.id.encode_3:
                setData("encode", MediaRecorder.AudioEncoder.AMR_WB);
                setEncorderUI(R.id.encode_3);
                break;
            case R.id.encode_4:
                setData("encode", MediaRecorder.AudioEncoder.DEFAULT);
                setEncorderUI(R.id.encode_4);
                break;
            case R.id.hz_0:
                setData("hz", 48000);
                setHzUI(R.id.hz_0);
                break;
            case R.id.hz_1:
                setData("hz", 44100);
                setHzUI(R.id.hz_1);
                break;
            case R.id.hz_2:
                setData("hz", 22050);
                setHzUI(R.id.hz_2);
                break;
            case R.id.hz_3:
                setData("hz", 16000);
                setHzUI(R.id.hz_3);
                break;
            case R.id.hz_4:
                setData("hz", 11025);
                setHzUI(R.id.hz_4);
                break;
            case R.id.hz_5:
                setData("hz", 4000);
                setHzUI(R.id.hz_5);
                break;
            case R.id.hz_6:
                setData("hz", 8000);
                setHzUI(R.id.hz_6);
                break;
            case R.id.encoding_1:
                setData("encoding", AudioFormat.ENCODING_DEFAULT);
                setEncordingUI(R.id.encoding_1);
                break;
            case R.id.encoding_2:
                setData("encoding", AudioFormat.ENCODING_PCM_8BIT);
                setEncordingUI(R.id.encoding_2);
                break;
            case R.id.encoding_3:
                setData("encoding", AudioFormat.ENCODING_PCM_16BIT);
                setEncordingUI(R.id.encoding_3);
                break;
            case R.id.encoding_4:
                setData("encoding", AudioFormat.ENCODING_PCM_FLOAT);
                setEncordingUI(R.id.encoding_4);
                break;
            case R.id.encoding_5:
//                setData("encoding", AudioFormat.ENCODING_DTS);
                setEncordingUI(R.id.encoding_5);
                break;
            case R.id.encoding_6:
//                setData("encoding", AudioFormat.ENCODING_DTS_HD);
                setEncordingUI(R.id.encoding_6);
                break;
            case R.id.encoding_7:
                setData("encoding", AudioFormat.ENCODING_INVALID);
                setEncordingUI(R.id.encoding_7);
                break;
            case R.id.encoding_8:
                setData("encoding", AudioFormat.ENCODING_AC3);
                setEncordingUI(R.id.encoding_8);
                break;
            case R.id.encoding_9:
                setData("encoding", AudioFormat.ENCODING_E_AC3);
                setEncordingUI(R.id.encoding_9);
                break;
            case R.id.channel_1:
                setData("channel", AudioFormat.CHANNEL_IN_MONO);
                setChannelUI(R.id.channel_1);
                break;
            case R.id.channel_2:
                setData("channel", AudioFormat.CHANNEL_IN_STEREO);
                setChannelUI(R.id.channel_2);
                break;
            case R.id.channel_3:
                setData("channel", AudioFormat.CHANNEL_IN_DEFAULT);
                setChannelUI(R.id.channel_3);
                break;
            case R.id.channel_4:
                setData("channel", AudioFormat.CHANNEL_INVALID);
                setChannelUI(R.id.channel_4);
                break;
            case R.id.channel_5:
                setData("channel", AudioFormat.CHANNEL_IN_FRONT);
                setChannelUI(R.id.channel_5);
                break;
            case R.id.channel_6:
                setData("channel", AudioFormat.CHANNEL_IN_BACK);
                setChannelUI(R.id.channel_6);
                break;
            case R.id.channel_7:
                setData("channel", AudioFormat.CHANNEL_IN_PRESSURE);
                setChannelUI(R.id.channel_7);
                break;
            case R.id.channel_8:
                setData("channel", AudioFormat.CHANNEL_IN_VOICE_DNLINK);
                setChannelUI(R.id.channel_8);
                break;
            case R.id.channel_9:
                setData("channel", AudioFormat.CHANNEL_IN_VOICE_UPLINK);
                setChannelUI(R.id.channel_9);
                break;
            case R.id.record_mode_0:
                setData("record_mode", 0);
                record_mode_0.setTextColor(getResources().getColor(R.color.red));
                record_mode_1.setTextColor(getResources().getColor(R.color.black));
                record_mode_2.setTextColor(getResources().getColor(R.color.black));
                break;
            case R.id.record_mode_1:
                setData("record_mode", 1);
                record_mode_0.setTextColor(getResources().getColor(R.color.black));
                record_mode_1.setTextColor(getResources().getColor(R.color.red));
                record_mode_2.setTextColor(getResources().getColor(R.color.black));
                break;
            case R.id.record_mode_2:
                setData("record_mode", 2);
                record_mode_0.setTextColor(getResources().getColor(R.color.black));
                record_mode_1.setTextColor(getResources().getColor(R.color.black));
                record_mode_2.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void setData(String key, int value) {
        SharedPreferencesUtil.put(this, key, value);
    }
    private void setSourceUI(int id) {
        source_1.setTextColor(getResources().getColor(R.color.black));
        source_2.setTextColor(getResources().getColor(R.color.black));
        source_3.setTextColor(getResources().getColor(R.color.black));
        source_4.setTextColor(getResources().getColor(R.color.black));
        source_5.setTextColor(getResources().getColor(R.color.black));
        source_6.setTextColor(getResources().getColor(R.color.black));
        source_7.setTextColor(getResources().getColor(R.color.black));
        switch (id) {
            case R.id.source_1:
                source_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.source_2:
                source_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.source_3:
                source_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.source_4:
                source_4.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.source_5:
                source_5.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.source_6:
                source_6.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.source_7:
                source_7.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void showSourceUI(int source) {
        source_1.setTextColor(getResources().getColor(R.color.black));
        source_2.setTextColor(getResources().getColor(R.color.black));
        source_3.setTextColor(getResources().getColor(R.color.black));
        source_4.setTextColor(getResources().getColor(R.color.black));
        source_5.setTextColor(getResources().getColor(R.color.black));
        source_6.setTextColor(getResources().getColor(R.color.black));
        source_7.setTextColor(getResources().getColor(R.color.black));
        switch (source) {
            case 0:
                source_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case 1:
                source_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case 4:
                source_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case 7:
                source_4.setTextColor(getResources().getColor(R.color.red));
                break;
            case 3:
                source_5.setTextColor(getResources().getColor(R.color.red));
                break;
            case 6:
                source_6.setTextColor(getResources().getColor(R.color.red));
                break;
            case 2:
                source_7.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void setFormatUI(int id) {
        format_1.setTextColor(getResources().getColor(R.color.black));
        format_2.setTextColor(getResources().getColor(R.color.black));
        format_3.setTextColor(getResources().getColor(R.color.black));
        format_4.setTextColor(getResources().getColor(R.color.black));
        format_5.setTextColor(getResources().getColor(R.color.black));
        format_6.setTextColor(getResources().getColor(R.color.black));
        switch (id) {
            case R.id.format_1:
                format_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.format_2:
                format_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.format_3:
                format_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.format_4:
                format_4.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.format_5:
                format_5.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.format_6:
                format_6.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void showFormatUI(int format) {
        format_1.setTextColor(getResources().getColor(R.color.black));
        format_2.setTextColor(getResources().getColor(R.color.black));
        format_3.setTextColor(getResources().getColor(R.color.black));
        format_4.setTextColor(getResources().getColor(R.color.black));
        format_5.setTextColor(getResources().getColor(R.color.black));
        format_6.setTextColor(getResources().getColor(R.color.black));
        switch (format) {
            case 6:
                format_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case 3:
                format_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case 4:
                format_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case 2:
                format_4.setTextColor(getResources().getColor(R.color.red));
                break;
            case 1:
                format_5.setTextColor(getResources().getColor(R.color.red));
                break;
            case 0:
                format_6.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void setEncorderUI(int id) {
        encorder_1.setTextColor(getResources().getColor(R.color.black));
        encorder_2.setTextColor(getResources().getColor(R.color.black));
        encorder_3.setTextColor(getResources().getColor(R.color.black));
        encorder_4.setTextColor(getResources().getColor(R.color.black));
        switch (id) {
            case R.id.encode_1:
                encorder_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encode_2:
                encorder_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encode_3:
                encorder_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encode_4:
                encorder_4.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void showEncorderUI(int encode) {
        encorder_1.setTextColor(getResources().getColor(R.color.black));
        encorder_2.setTextColor(getResources().getColor(R.color.black));
        encorder_3.setTextColor(getResources().getColor(R.color.black));
        encorder_4.setTextColor(getResources().getColor(R.color.black));
        switch (encode) {
            case 3:
                encorder_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case 1:
                encorder_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case 2:
                encorder_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case 0:
                encorder_4.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void setHzUI(int id) {
        hz_0.setTextColor(getResources().getColor(R.color.black));
        hz_1.setTextColor(getResources().getColor(R.color.black));
        hz_2.setTextColor(getResources().getColor(R.color.black));
        hz_3.setTextColor(getResources().getColor(R.color.black));
        hz_4.setTextColor(getResources().getColor(R.color.black));
        hz_5.setTextColor(getResources().getColor(R.color.black));
        hz_6.setTextColor(getResources().getColor(R.color.black));
        switch (id) {
            case R.id.hz_0:
                hz_0.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.hz_1:
                hz_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.hz_2:
                hz_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.hz_3:
                hz_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.hz_4:
                hz_4.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.hz_5:
                hz_5.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.hz_6:
                hz_6.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void showHzUI(int hz) {
        hz_0.setTextColor(getResources().getColor(R.color.black));
        hz_1.setTextColor(getResources().getColor(R.color.black));
        hz_2.setTextColor(getResources().getColor(R.color.black));
        hz_3.setTextColor(getResources().getColor(R.color.black));
        hz_4.setTextColor(getResources().getColor(R.color.black));
        hz_5.setTextColor(getResources().getColor(R.color.black));
        hz_6.setTextColor(getResources().getColor(R.color.black));
        switch (hz) {
            case 48000:
                hz_0.setTextColor(getResources().getColor(R.color.red));
                break;
            case 44100:
                hz_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case 22050:
                hz_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case 16000:
                hz_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case 11025:
                hz_4.setTextColor(getResources().getColor(R.color.red));
                break;
            case 4000:
                hz_5.setTextColor(getResources().getColor(R.color.red));
                break;
            case 8000:
                hz_6.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void setEncordingUI(int id) {
        encording_1.setTextColor(getResources().getColor(R.color.black));
        encording_2.setTextColor(getResources().getColor(R.color.black));
        encording_3.setTextColor(getResources().getColor(R.color.black));
        encording_4.setTextColor(getResources().getColor(R.color.black));
        encording_5.setTextColor(getResources().getColor(R.color.black));
        encording_6.setTextColor(getResources().getColor(R.color.black));
        encording_7.setTextColor(getResources().getColor(R.color.black));
        encording_8.setTextColor(getResources().getColor(R.color.black));
        encording_9.setTextColor(getResources().getColor(R.color.black));
        switch (id) {
            case R.id.encoding_1:
                encording_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encoding_2:
                encording_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encoding_3:
                encording_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encoding_4:
                encording_4.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encoding_5:
                encording_5.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encoding_6:
                encording_6.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encoding_7:
                encording_7.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encoding_8:
                encording_8.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.encoding_9:
                encording_9.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void showEncordingUI(int encoding) {
        encording_1.setTextColor(getResources().getColor(R.color.black));
        encording_2.setTextColor(getResources().getColor(R.color.black));
        encording_3.setTextColor(getResources().getColor(R.color.black));
        encording_4.setTextColor(getResources().getColor(R.color.black));
        encording_5.setTextColor(getResources().getColor(R.color.black));
        encording_6.setTextColor(getResources().getColor(R.color.black));
        encording_7.setTextColor(getResources().getColor(R.color.black));
        encording_8.setTextColor(getResources().getColor(R.color.black));
        encording_9.setTextColor(getResources().getColor(R.color.black));
        switch (encoding) {
            case 1:
                encording_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case 3:
                encording_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case 2:
                encording_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case 4:
                encording_4.setTextColor(getResources().getColor(R.color.red));
                break;
            case 7:
                encording_5.setTextColor(getResources().getColor(R.color.red));
                break;
            case 8:
                encording_6.setTextColor(getResources().getColor(R.color.red));
                break;
            case 0:
                encording_7.setTextColor(getResources().getColor(R.color.red));
                break;
            case 5:
                encording_8.setTextColor(getResources().getColor(R.color.red));
                break;
            case 6:
                encording_9.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void setChannelUI(int id) {
        channel_1.setTextColor(getResources().getColor(R.color.black));
        channel_2.setTextColor(getResources().getColor(R.color.black));
        channel_3.setTextColor(getResources().getColor(R.color.black));
        channel_4.setTextColor(getResources().getColor(R.color.black));
        channel_5.setTextColor(getResources().getColor(R.color.black));
        channel_6.setTextColor(getResources().getColor(R.color.black));
        channel_7.setTextColor(getResources().getColor(R.color.black));
        channel_8.setTextColor(getResources().getColor(R.color.black));
        channel_9.setTextColor(getResources().getColor(R.color.black));
        switch (id) {
            case R.id.channel_1:
                channel_1.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.channel_2:
                channel_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.channel_3:
                channel_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.channel_4:
                channel_4.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.channel_5:
                channel_5.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.channel_6:
                channel_6.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.channel_7:
                channel_7.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.channel_8:
                channel_8.setTextColor(getResources().getColor(R.color.red));
                break;
            case R.id.channel_9:
                channel_9.setTextColor(getResources().getColor(R.color.red));
                break;
        }
    }

    private void showChannelUI(int channel) {
        channel_1.setTextColor(getResources().getColor(R.color.black));
        channel_2.setTextColor(getResources().getColor(R.color.black));
        channel_3.setTextColor(getResources().getColor(R.color.black));
        channel_4.setTextColor(getResources().getColor(R.color.black));
        channel_5.setTextColor(getResources().getColor(R.color.black));
        channel_6.setTextColor(getResources().getColor(R.color.black));
        channel_7.setTextColor(getResources().getColor(R.color.black));
        channel_8.setTextColor(getResources().getColor(R.color.black));
        channel_9.setTextColor(getResources().getColor(R.color.black));
        switch (channel) {
            case 16:
                channel_1.setTextColor(getResources().getColor(R.color.red));
                channel_5.setTextColor(getResources().getColor(R.color.red));
                break;
            case 12:
                channel_2.setTextColor(getResources().getColor(R.color.red));
                break;
            case 1:
                channel_3.setTextColor(getResources().getColor(R.color.red));
                break;
            case 0:
                channel_4.setTextColor(getResources().getColor(R.color.red));
                break;
            case 32:
                channel_6.setTextColor(getResources().getColor(R.color.red));
                break;
            case 1024:
                channel_7.setTextColor(getResources().getColor(R.color.red));
                break;
            case 32768:
                channel_8.setTextColor(getResources().getColor(R.color.red));
                break;
            case 16384:
                channel_9.setTextColor(getResources().getColor(R.color.red));
                break;
        }

    }
}
