/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.cellbots.logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.Size;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * View that handles the picture taking functionality.
 *
 * @author clchen@google.com (Charles L. Chen)
 */
public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {

	private static final String TAG = "CameraPreview";
	private static final int CAMERA_ORIENTATION_DEGREES = 90;

	private int mCameraID = CameraInfo.CAMERA_FACING_BACK;
	private Camera mCamera;
	private final LoggerApplication mApp;

	public CameraPreview(Context context, AttributeSet attrs) {
		super(context, attrs);

		mApp = (LoggerApplication) context.getApplicationContext();
		getHolder().addCallback(this);
	}

	public void selectCamera(int id) {
		mCameraID = id;
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		Log.i(TAG, "CameraPreview.surfaceCreated");

		mCamera = Camera.open(mCameraID);
		try {
			mCamera.setPreviewDisplay(holder);
			mCamera.setDisplayOrientation(CAMERA_ORIENTATION_DEGREES);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		Log.i(TAG, "CameraPreview.surfaceChanged");
		mCamera.startPreview();
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.i(TAG, "CameraPreview.surfaceDestroyed");
		release();
	}

	private boolean mTakingPictures = false;
	private long mDelay = 0;
	private int mPictureCount = 0;

	/**
	 * Take pictures.
	 * @param delay
	 */
	public void takePictures(long delay) {
		if (mTakingPictures)
			return;

		File dir = new File(mApp.getPicturesDirectoryPath());
		if (!dir.exists()) {
			dir.mkdirs();
		}

		mDelay = delay;
		mTakingPictures = true;
		runPictureTakingLoop();
	}

	private MediaRecorder mVideoRecorder = null;
	private FileOutputStream mOutputStream = null;

	/**
	 * Record camera preview.
	 */
	public void recordVideo() {
		if (mVideoRecorder == null)
			mVideoRecorder = createVideoRecorder();

		String path = mApp.getVideoFilepath();
		Log.i(TAG, "Video file to use: " + path);

		final File directory = new File(path).getParentFile();
		if (!directory.exists() && !directory.mkdirs()) {
			Log.e(TAG, "Path to file could not be created. " + directory.getAbsolutePath());
		}

		Size previewSize = mCamera.getParameters().getPreviewSize();
		mCamera.unlock();

		try {
			mOutputStream = new FileOutputStream(path);
			mVideoRecorder.setCamera(mCamera);
			mVideoRecorder.setOutputFile(mOutputStream.getFD());
			mVideoRecorder.setVideoSize(previewSize.width, previewSize.height);
			mVideoRecorder.setVideoFrameRate(20);
			mVideoRecorder.setPreviewDisplay(getHolder().getSurface());
			mVideoRecorder.prepare();
		} catch (IllegalStateException e) {
			Log.e(TAG, e.toString(), e);
		} catch (IOException e) {
			Log.e(TAG, e.toString(), e);
		}

		mVideoRecorder.start();
	}

	/**
	 * Stop recording of picture or video.
	 */
	public void stopRecording() {
		mTakingPictures = false;
		if (mVideoRecorder != null) {
			mVideoRecorder.stop();
			mCamera.lock();
		}
	}

	/**
	 * Release resources (camera, file stream and recorder).
	 */
	public void release() {
		if (mVideoRecorder != null) {
			mVideoRecorder.reset();
			mVideoRecorder.release();
			mVideoRecorder = null;
		}

		if (mOutputStream != null) {
			try {
				mOutputStream.close();
				mOutputStream = null;
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		if (mCamera != null) {
			mCamera.stopPreview();
			mCamera.release();
			mCamera = null;
		}
	}

	public int getPictureCount() {
		return mPictureCount;
	}

	private void runPictureTakingLoop() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					if (!mTakingPictures) return;
					Thread.sleep(mDelay);
					if (!mTakingPictures) return;

					mCamera.takePicture(null, null, new PictureCallback() {
						@Override
						public void onPictureTaken(byte[] data, Camera camera) {
							String path = mApp.getPicturesDirectoryPath() + "/" + System.currentTimeMillis() + ".jpg";
							Log.d(TAG, "Tacking picture: " + path);
							try {
								FileOutputStream outStream = new FileOutputStream(path);
								outStream.write(data);
								outStream.close();
								mPictureCount++;
							} catch (FileNotFoundException e) {
								e.printStackTrace();
							} catch (IOException e) {
								e.printStackTrace();
							} finally {
                                mCamera.startPreview();
								runPictureTakingLoop();
							}
						}
					});
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}).start();
	}

	private MediaRecorder createVideoRecorder() {
		MediaRecorder recorder = new MediaRecorder();
		recorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
			@Override
			public void onError(MediaRecorder mr, int what, int extra) {
				Log.e(TAG, "Error received in media recorder: " + what + ", " + extra);
			}
		});
		recorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
			@Override
			public void onInfo(MediaRecorder mr, int what, int extra) {
				Log.e(TAG, "Info received from media recorder: " + what + ", " + extra);
			}
		});

		try {
			recorder.setOrientationHint(CAMERA_ORIENTATION_DEGREES);
		} catch (NoSuchMethodError e) {
			// Method call not available below API level 9. The recorded
			// video will be rotated.
			e.printStackTrace();
		}
		recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
		recorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));

		return recorder;
	}
}
