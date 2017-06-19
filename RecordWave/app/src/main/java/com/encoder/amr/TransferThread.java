package com.encoder.amr;

import java.io.IOException;
import java.io.InputStream;

import android.content.Context;
import android.os.Environment;

public class TransferThread extends Thread{
	
	private TransferCallback callback;
	private Context context;
	private String path;
	public TransferThread(Context context, String path, TransferCallback callback){
		this.callback = callback;
		this.context = context;
		this.path = path;
	}
	
	@Override
	public void run() {
		transfer();
	}
	
	private void transfer(){
		String rootPath = Environment.getExternalStorageDirectory().getPath();
        String amrPath = rootPath + "/test.amr";
        try {
//            InputStream pcmStream = context.getAssets().open("test.pcm");
//            AmrEncoder.pcm2Amr(pcmStream, amrPath);
			AmrEncoder.pcm2Amr(path, path.replace(".pcm", ".amr"));
            callback.onSuccess();
        } catch (Exception e) {
        	callback.onFailed();
            e.printStackTrace();
        }
	}
	
	
	public static interface TransferCallback{
		
		void onSuccess();
		
		void onFailed();
	}

}
