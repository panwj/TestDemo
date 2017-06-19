package com.czt.mp3recorder;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import com.encoder.amr.AmrEncoder;

import utils.SharedPreferencesUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Created by panwenjuan on 17-6-2.
 */
public class AudioRecorderPcm {

    private boolean isFailRecording = false;

    private String mSaveDir;
    private String mFileUrl;

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

    /**
     * 自定义 每160帧作为一个周期，通知一下需要进行编码
     */
    private static final int FRAME_COUNT = 160;
    public static final int ERROR_TYPE = 22;

    private static final String TAG = "AudioRecorderPcm";

    private Context mContext;

    private AudioRecord mAudioRecord = null;

    private File mRecordFile;
    private ArrayList<Short> dataList;
    private Handler errorHandler;


    private int mBufferSize;
    private short[] mPCMBuffer;
    private boolean mIsRecording = false;
    private int mMaxSize;
    private boolean mSendError;
    private boolean mPause;

    /**
     * Default constructor. Setup recorder with default sampling rate 1 channel,
     * 16 bits pcm
     *
     * @param recordFile target file
     */
    public AudioRecorderPcm(Context context, File recordFile) {
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

        mIsRecording = true; // 提早，防止init或startRecording被多次调用
        initAudioRecorder();
        try {
            if (mAudioRecord == null) {
                Toast.makeText(mContext, "audio recording error", Toast.LENGTH_SHORT).show();
                return;
            }
            mAudioRecord.startRecording();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        new Thread() {
            boolean isError = false;

            @Override
            public void run() {
                //设置线程权限
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                while (mIsRecording) {
                    writeDateToFile();
                }
                try {
                    // release and finalize audioRecord
                    mAudioRecord.stop();
                    mAudioRecord.release();
                    mAudioRecord = null;
                } catch (Exception ex) {
                    ex.printStackTrace();
                    isError = true;
                    mIsRecording = false;
                }
                // stop the encoding thread and try to wait
                // until the thread finishes its job

            }

        }.start();
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

    public void stop() {
        mPause = false;
        mIsRecording = false;
    }

    public boolean isRecording() {
        return mIsRecording;
    }

    /**
     * Initialize audio recorder
     */
    private void initAudioRecorder() throws IOException {

        int test_source = (int) SharedPreferencesUtil.get(mContext, "source", DEFAULT_AUDIO_SOURCE);
        int test_sampling_rate = (int) SharedPreferencesUtil.get(mContext, "hz", DEFAULT_SAMPLING_RATE);
        int test_channel_congif = (int) SharedPreferencesUtil.get(mContext, "channel", DEFAULT_CHANNEL_CONFIG);
        int test_bit = (int) SharedPreferencesUtil.get(mContext, "encoding", DEFAULT_AUDIO_FORMAT.getAudioFormat());
        Log.d(TAG, "test_source = " + test_source + "  test_sampling_rate = " + test_sampling_rate
                + "  test_channel_congif = " + test_channel_congif + "  test_bit = " + test_bit);
        try {
            mBufferSize = AudioRecord.getMinBufferSize(test_sampling_rate,
                    test_channel_congif, test_bit);

            int bytesPerFrame = DEFAULT_AUDIO_FORMAT.getBytesPerFrame();
        /* Get number of samples. Calculate the buffer size
         * (round up to the factor of given frame size)
		 * 使能被整除，方便下面的周期性通知
		 * */
            int frameSize = mBufferSize / bytesPerFrame;
            if (frameSize % FRAME_COUNT != 0) {
                frameSize += (FRAME_COUNT - frameSize % FRAME_COUNT);
                mBufferSize = frameSize * bytesPerFrame;
            }

		/* Setup audio recorder */
            mAudioRecord = new AudioRecord(test_source,
                    test_sampling_rate, test_channel_congif, test_bit, mBufferSize);
        } catch (Exception e) {
            Log.d(TAG, "initAudioRecorder e = " + e.toString());
        }
    }


    public static void deleteFile(String filePath) {
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

   /* private void stopAudioRecord() {
        if (mAudioRecorder != null) {
            destroyThread();
            mAudioRecorder.stop();
        }

        isFailRecording = false;

        mAudioRecorder = null;
    }

    private void destroyThread() {
        try {
            isRecorder = false;
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
    }*

    /**
     * 这里将数据写入文件，但是并不能播放，因为AudioRecord获得的音频是原始的裸音频，
     * 如果需要播放就必须加入一些格式或者编码的头信息。但是这样的好处就是你可以对音频的 裸数据进行处理，比如你要做一个爱说话的TOM
     * 猫在这里就进行音频的处理，然后重新封装 所以说这样得到的音频比较容易做一些音频的处理。
     */

    private void writeDateToFile() {
        Log.d(TAG, "writeDateTOFile()--->Enter");
        // new一个byte数组用来存一些字节数据，大小为缓冲区大小

        byte[] audiodata = new byte[mBufferSize];
        FileOutputStream fos = null;
        int readsize = 0;
        try {
//            File file = new File(mFileUrl);
            if (mRecordFile.exists()) {
                mRecordFile.delete();
            }
            fos = new FileOutputStream(mRecordFile);// 建立一个可存取字节的文件

        } catch (Exception e) {
            e.printStackTrace();
        }
        while (mIsRecording == true) {
            readsize = mAudioRecord.read(audiodata, 0, mBufferSize);
            if (AudioRecord.ERROR_INVALID_OPERATION != readsize && fos!=null) {
                try {

                    fos.write(audiodata);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        try {
            if(fos != null)
                fos.close();// 关闭写入流
            String path = mRecordFile.getAbsolutePath();
            //encording .wav
//            convertAudioFiles(path, path.replace(".pcm", ".wav"));
            //encording .amr  8000 hz ---> ok
//            AmrEncoder.pcm2Amr(path, path.replace(".pcm", ".amr"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        Log.d(TAG, "writeDateTOFile()--->Exit");
    }

    // 这里得到可播放的音频文件
    private void copyWaveFile(String inFilename, String outFilename) {
        Log.d(TAG, "copyWaveFile()--->Enter");
        FileInputStream in = null;
        FileOutputStream out = null;
        long totalAudioLen = 0;
        long totalDataLen = totalAudioLen + 36;
        long longSampleRate = 44100;
        int channels = 1;

        Log.d(TAG, "copyWaveFile()  channels = " + channels + "     longSampleRate = " + longSampleRate);

        long byteRate = 16 * 44100 * channels / 8;

        byte[] data = new byte[mBufferSize];
        try {
            in = new FileInputStream(inFilename);
            out = new FileOutputStream(outFilename);
            totalAudioLen = in.getChannel().size();
            totalDataLen = totalAudioLen + 36;
            WriteWaveFileHeader(out, totalAudioLen, totalDataLen,
                    longSampleRate, channels, byteRate);
            while (in.read(data) != -1) {
                out.write(data);
            }
            in.close();
            out.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {

        }
        Log.d(TAG, "copyWaveFile()--->Exit");
    }

    /**
     * 这里提供一个头信息。插入这些信息就可以得到可以播放的文件。
     * 为我为啥插入这44个字节，这个还真没深入研究，不过你随便打开一个wav
     * 音频的文件，可以发现前面的头文件可以说基本一样哦。每种格式的文件都有
     * 自己特有的头文件。
     */
    private void WriteWaveFileHeader(FileOutputStream out, long totalAudioLen,
                                     long totalDataLen, long longSampleRate, int channels, long byteRate)
            throws IOException {
        Log.d(TAG, "WriteWaveFileHeader()--->Enter");
        byte[] header = new byte[44];
        header[0] = 'R'; // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f'; // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16; // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1; // format = 1
        header[21] = 0;
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * 16 / 8); // block align
        header[33] = 0;
        header[34] = 16; // bits per sample
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);
        out.write(header, 0, 44);
        Log.d(TAG, "WriteWaveFileHeader()--->Exit");
    }


    public void convertAudioFiles(String src, String target) {
        Toast.makeText(mContext, "to .wav start", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "convertAudioFiles()  enter");
        FileInputStream fis = null;
        FileOutputStream fos = null;

        try {
            fis = new FileInputStream(src);
            fos = new FileOutputStream(target);

            //计算长度
            byte[] buf = new byte[1024 * 4];
//            byte[] buf = new byte[bufferSizeInBytes];
            int size = fis.read(buf);
            int PCMSize = 0;
            while (size != -1) {
                PCMSize += size;
                size = fis.read(buf);
            }
            fis.close();

            //填入参数，比特率等等。这里用的是16位单声道 44100 hz
            WaveHeader header = new WaveHeader();
            //长度字段 = 内容的大小（PCMSize) + 头部字段的大小(不包括前面4字节的标识符RIFF以及fileLength本身的4字节)
            header.fileLength = PCMSize + (44 - 8);
            header.FmtHdrLeth = 16;
            header.BitsPerSample = 16;
            header.Channels = 1;
            header.FormatTag = 0x0001;
            header.SamplesPerSec = 44100;
            header.BlockAlign = (short) (header.Channels * header.BitsPerSample / 8);
            header.AvgBytesPerSec = header.BlockAlign * header.SamplesPerSec;
            header.DataHdrLeth = PCMSize;

            byte[] h = header.getHeader();

            assert h.length == 44; //WAV标准，头部应该是44字节
            //write header
            fos.write(h, 0, h.length);
            //write data stream
            fis = new FileInputStream(src);
            size = fis.read(buf);
            while (size != -1) {
                fos.write(buf, 0, size);
                size = fis.read(buf);
            }
            Toast.makeText(mContext, "to .wav success", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(mContext, "to .wav failed", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "------------- " + e.toString());
        } finally {
            try {
                fis.close();
                fos.close();
            } catch (Exception o) {

            }

        }
        Log.d(TAG, "convertAudioFiles()  exits");
    }

    public class WaveHeader {
        public final char fileID[] = {'R', 'I', 'F', 'F'};
        public int fileLength;
        public char wavTag[] = {'W', 'A', 'V', 'E'};
        public char FmtHdrID[] = {'f', 'm', 't', ' '};
        public int FmtHdrLeth;
        public short FormatTag;
        public short Channels;
        public int SamplesPerSec;
        public int AvgBytesPerSec;
        public short BlockAlign;
        public short BitsPerSample;
        public char DataHdrID[] = {'d', 'a', 't', 'a'};
        public int DataHdrLeth;

        public byte[] getHeader() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            WriteChar(bos, fileID);
            WriteInt(bos, fileLength);
            WriteChar(bos, wavTag);
            WriteChar(bos, FmtHdrID);
            WriteInt(bos, FmtHdrLeth);
            WriteShort(bos, FormatTag);
            WriteShort(bos, Channels);
            WriteInt(bos, SamplesPerSec);
            WriteInt(bos, AvgBytesPerSec);
            WriteShort(bos, BlockAlign);
            WriteShort(bos, BitsPerSample);
            WriteChar(bos, DataHdrID);
            WriteInt(bos, DataHdrLeth);
            bos.flush();
            byte[] r = bos.toByteArray();
            bos.close();
            return r;
        }

        private void WriteShort(ByteArrayOutputStream bos, int s) throws IOException {
            byte[] mybyte = new byte[2];
            mybyte[1] = (byte) ((s << 16) >> 24);
            mybyte[0] = (byte) ((s << 24) >> 24);
            bos.write(mybyte);
        }


        private void WriteInt(ByteArrayOutputStream bos, int n) throws IOException {
            byte[] buf = new byte[4];
            buf[3] = (byte) (n >> 24);
            buf[2] = (byte) ((n << 8) >> 24);
            buf[1] = (byte) ((n << 16) >> 24);
            buf[0] = (byte) ((n << 24) >> 24);
            bos.write(buf);
        }

        private void WriteChar(ByteArrayOutputStream bos, char[] id) {
            for (int i = 0; i < id.length; i++) {
                char c = id[i];
                bos.write(c);
            }
        }
    }
}
