/**
 * 
 */
package utils;

/** <p>Title: PhoneInfo</p>
 * <p>Description: </p>
 * <p>Copyright: Copyright (c) 2016</p>
 * <p>Company: cnTomorrow</p>
 * @author ���ľ�
 * @version 1.0 
 */
public class PhoneInfo {
	
	public String mRelease="";
	public String mModel="";
	public String mDeviceName="";

	public int mSdk_int;
	public int mAudioSource;
	public int mAudioFormat;

	public String getmRelease() {
		return mRelease;
	}
	public void setmRelease(String mRelease) {
		this.mRelease = mRelease;
	}
	public String getmModel() {
		return mModel;
	}
    public String getDeviceName() {
        return mDeviceName;
    }
	public void setmModel(String mModel) {
		this.mModel = mModel;
	}
    public void setDeviceName(String name) {
        this.mDeviceName = name;
    }
	public int getmSdk_int() {
		return mSdk_int;
	}
	public void setmSdk_int(int mSdk_int) {
		this.mSdk_int = mSdk_int;
	}
	public int getmAudioSource() {
		return mAudioSource;
	}
	public void setmAudioSource(int mAudioSource) {
		this.mAudioSource = mAudioSource;
	}
	public int getmAudioFormat() {
		return mAudioFormat;
	}
	public void setmAudioFormat(int mAudioFormat) {
		this.mAudioFormat = mAudioFormat;
	}
	public PhoneInfo() {
		super();
		// TODO Auto-generated constructor stub
	}

	public PhoneInfo(String mRelease, String deviceName, int mSdk_int,
					 int mAudioSource, int mAudioFormat) {
		super();
		this.mRelease = mRelease;
		this.mDeviceName = deviceName;
		this.mSdk_int = mSdk_int;
		this.mAudioSource = mAudioSource;
		this.mAudioFormat = mAudioFormat;
	}

    public PhoneInfo(String mRelease, String deviceName, String model, int mSdk_int,
					 int mAudioSource, int mAudioFormat) {
        super();
        this.mRelease = mRelease;
        this.mDeviceName = deviceName;
        this.mSdk_int = mSdk_int;
        this.mAudioSource = mAudioSource;
        this.mAudioFormat = mAudioFormat;
        this.mModel = model;
    }

}
