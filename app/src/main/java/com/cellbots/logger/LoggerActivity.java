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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.zip.Deflater;

import org.json.JSONException;
import org.json.JSONObject;

import com.cellbots.logger.GpsManager.GpsManagerListener;
import com.cellbots.logger.RemoteControl.Command;
import com.cellbots.logger.WapManager.ScanResults;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.Camera.CameraInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SlidingDrawer;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Main activity for a data gathering tool. This tool enables recording video
 * and collecting data from mSensors. Data is stored in:
 * /sdcard/SmartphoneLoggerData/.
 *
 * @author clchen@google.com (Charles L. Chen)
 */

public class LoggerActivity extends Activity {
	/*
	 * Constants
	 */
	public static final String TAG = "LoggerActivity";

	public static final String EXTRA_MODE = "MODE";
    public static final String EXTRA_CAMERA = "CAMERA_ID";
    public static final String EXTRA_CAMERA_RESOLUTION = "CAMERA_RESOLUTION";
	public static final String EXTRA_PICTURE_DELAY = "PICTURE_DELAY";
	public static final String EXTRA_USE_ZIP = "USE_ZIP";

	public static final int CAMERA_VIDEO_FRONT = CameraInfo.CAMERA_FACING_FRONT;
	public static final int CAMERA_VIDEO_BACK = CameraInfo.CAMERA_FACING_BACK;
	public static final int MODE_PICTURES = 0;
    public static final int MODE_VIDEO = 1;

	private static final int UI_BAR_MAX_TOP_PADDING = 275;
	private static final float TEMPERATURE_MAX = 500;

	// max file size. if this is set to zero, only 1 .zip file is created
	protected static final int MAX_OUTPUT_ZIP_CHUNK_SIZE = 50 * 1024 * 1024;
	private static final int PROGRESS_ID = 123122312;

	/*
	 * App state
	 */
	private int mMode;
	private volatile Boolean mIsRecording;
	private boolean mUseZip;
	private long mStartRecTime = 0;
	private long mDelay = 0;
	private LoggerApplication mApp;

	/*
	 * UI Elements
	 */

	private CameraPreview mCameraView;
	private TextView mAccelXTextView;
	private TextView mAccelYTextView;
	private TextView mAccelZTextView;
	private TextView mGyroXTextView;
	private TextView mGyroYTextView;
	private TextView mGyroZTextView;
	private TextView mMagXTextView;
	private TextView mMagYTextView;
	private TextView mMagZTextView;

	private BarImageView mBatteryTempBarImageView;
	private TextView mBatteryTempTextView;
	private TextView mBatteryTempSpacerTextView;
	private BarImageView mStorageBarImageView;
	private TextView mStorageTextView;
	private TextView mStorageSpacerTextView;
	private SlidingDrawer mDiagnosticsDrawer;
	private SlidingDrawer mDataDrawer;

	private LinearLayout mFlashingRecGroup;
	private TextView mRecordInfo;
	private TextView mGpsLocationView;

	/**
	 * Sensors
	 */
	private SensorManager mSensorManager;
    private List<Sensor> mSensors;
    private StatFs mStatFs;
    private int mFreeSpacePct;
    private GpsManager mGpsManager;
    private WapManager mWapManager;
    private RemoteControl mRemoteControl;

    /**
     * Sensor writers
     */
	private BufferedWriter mBatteryTempWriter;
	private BufferedWriter mBatteryLevelWriter;
	private BufferedWriter mBatteryVoltageWriter;
	private BufferedWriter mWifiWriter;
    private BufferedWriter mGpsLocationWriter;
    private BufferedWriter mGpsStatusWriter;
    private BufferedWriter mGpsNmeaWriter;
    private HashMap<String, BufferedWriter> mSensorLogFileWriters;

	/*
	 * Event handlers
	 */
	private SensorEventListener mSensorEventListener = new SensorEventListener() {
		@Override
		public void onSensorChanged(SensorEvent event) {
			Sensor sensor = event.sensor;
			updateSensorUi(sensor.getType(), event.accuracy, event.values);
			synchronized (mIsRecording) {
				if (!mIsRecording) {
                    return;
                }
			}

            String valuesStr = "";
            for (int i = 0; i < event.values.length; i++) {
                valuesStr += event.values[i] + ",";
            }
            BufferedWriter writer = mSensorLogFileWriters.get(sensor.getName());
            try {
                writer.write(event.timestamp + "," + event.accuracy + "," + valuesStr + "\n");
                writer.flush();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (NullPointerException e) {
                e.printStackTrace();
            }
		}

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
		}
	};

	private BroadcastReceiver batteryBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			// Display and log the temperature
			int batteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
			float percentage = batteryTemp / TEMPERATURE_MAX;

			mBatteryTempBarImageView.setPercentage(percentage);
			int paddingTop = (int) ((1.0 - percentage) * UI_BAR_MAX_TOP_PADDING);
			mBatteryTempTextView.setText((batteryTemp / 10) + "°C");
			mBatteryTempSpacerTextView.setPadding(mBatteryTempSpacerTextView.getPaddingLeft(), paddingTop,
					mBatteryTempSpacerTextView.getPaddingRight(), mBatteryTempSpacerTextView.getPaddingBottom());

			synchronized (mIsRecording) {
				if (!mIsRecording)
					return;
			}

            try {
                mBatteryTempWriter.write(System.currentTimeMillis() + "," + batteryTemp + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Log the battery level
            int batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            try {
                mBatteryLevelWriter.write(System.currentTimeMillis() + "," + batteryLevel + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Log the battery voltage level
            int batteryVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            try {
                mBatteryVoltageWriter.write(System.currentTimeMillis() + "," + batteryVoltage + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
		}
	};

	private WapManager.WapManagerListener mWifiListener = new WapManager.WapManagerListener() {
		@Override
		public void onScanResults(long timestamp, ScanResults results) {
			synchronized (mIsRecording) {
				if (!mIsRecording)
					return;
			}

			try {
				// Convert results to a json object
				JSONObject obj = new JSONObject();
				obj.put("timestamp", timestamp);
				obj.put("results", new JSONObject(results));

				// Write that object to a file
				mWifiWriter.write(obj.toString());
				mWifiWriter.write("\n");
			} catch (JSONException e) {
				Log.e("LoggerActivity", "Error logging wifi results. JSON Error");
				e.printStackTrace();
			} catch (IOException e) {
				Log.e("LoggerActivity", "Error logging wifi results. IO error");
				e.printStackTrace();
			}
		}
	};

	private RemoteControl.CommandListener mCommandListener = new RemoteControl.CommandListener() {

		@Override
		public boolean onCommandReceived(Command c) throws Exception {
			if (c.command.equals("start")) {
				return onStartStopCommandReceived(c, true);
			} else if (c.command.equals("stop")) {
				return onStartStopCommandReceived(c, false);
			} else if (c.command.equals("status")) {
				if (mIsRecording)
					c.sendResponse("Status: RECORDING\n");
				else
					c.sendResponse("Status: STOPPED\n");
			}
			return false;
		}

		private boolean onStartStopCommandReceived(Command c, boolean start) {
			if (mIsRecording == start) {
				c.sendResponse("Recording already ");
				if (start)
					c.sendResponse("started.\n");
				else
					c.sendResponse("stopped.\n");
				return true;
			}

			final ImageButton recordButton = (ImageButton) findViewById(R.id.button_record);
			recordButton.performClick();

			return false;
		}
	};

	/*
	 * Runnables
	 */

	private Runnable updateRecTimeDisplay = new Runnable() {

		@Override
		public void run() {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					mRecordInfo.setVisibility(View.VISIBLE);
				}
			});

			while (true) {
				mStatFs = new StatFs(Environment.getExternalStorageDirectory().toString());
				final float percentage = (float) (mStatFs.getBlockCount() - mStatFs.getAvailableBlocks())
						/ (float) mStatFs.getBlockCount();
				final int paddingTop = (int) ((1.0 - percentage) * UI_BAR_MAX_TOP_PADDING);
				mFreeSpacePct = (int) (percentage * 100);
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						if (mFlashingRecGroup.getVisibility() == View.VISIBLE)
							mFlashingRecGroup.setVisibility(View.INVISIBLE);
						else
							mFlashingRecGroup.setVisibility(View.VISIBLE);

						if (mMode == MODE_PICTURES)
							mRecordInfo.setText("Pictures taken: " + mCameraView.getPictureCount());
						else
							mRecordInfo.setText(DateUtils.formatElapsedTime((System.currentTimeMillis() - mStartRecTime) / 1000));

						mStorageBarImageView.setPercentage(percentage);
						mStorageTextView = (TextView) findViewById(R.id.storage_text);
						mStorageTextView.setText(mFreeSpacePct + "%");
						mStorageSpacerTextView.setPadding(mStorageSpacerTextView.getPaddingLeft(), paddingTop,
								mStorageSpacerTextView.getPaddingRight(), mStorageSpacerTextView.getPaddingBottom());
					}
				});
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				synchronized (mIsRecording) {
                    if (!mIsRecording)
                        break;
				}
			}

			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					mFlashingRecGroup.setVisibility(View.INVISIBLE);
					mRecordInfo.setVisibility(View.INVISIBLE);
				}
			});
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		Log.i(TAG, "onCreate");

		super.onCreate(savedInstanceState);

		// Keep the screen on to make sure the phone stays awake.
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.preview_mode);

		mCameraView = (CameraPreview) findViewById(R.id.surface);
		mFlashingRecGroup = (LinearLayout) findViewById(R.id.flashingRecGroup);
		mGpsLocationView = (TextView) findViewById(R.id.gpsLocation);
		mRecordInfo = (TextView) findViewById(R.id.recordInfo);

		mMode = getIntent().getIntExtra(EXTRA_MODE, MODE_VIDEO);
		mFlashingRecGroup.setVisibility(View.INVISIBLE);
		mRecordInfo.setVisibility(View.INVISIBLE);

        // camera id and resolution
        int camID = getIntent().getIntExtra(EXTRA_CAMERA, CAMERA_VIDEO_BACK);
        String camRes = getIntent().getStringExtra(EXTRA_CAMERA_RESOLUTION);
        String camResDims[] = camRes.split("X");
        if (camResDims.length != 2) throw new AssertionError();
        int width = Integer.parseInt(camResDims[0]);
        int height = Integer.parseInt(camResDims[1]);

        mCameraView.selectCamera(camID, width, height);
		if (mMode == MODE_PICTURES) {
			mDelay = Math.max(0, getIntent().getIntExtra(EXTRA_PICTURE_DELAY, 30) * 1000);
			mCameraView = (CameraPreview) findViewById(R.id.surface);
		}

		mApp = (LoggerApplication) getApplication();
		mIsRecording = false;
		mUseZip = getIntent().getBooleanExtra(EXTRA_USE_ZIP, true);

		// Setup the initial available space
		mStatFs = new StatFs(Environment.getExternalStorageDirectory().toString());
		float percentage = (float) (mStatFs.getBlockCount() - mStatFs.getAvailableBlocks())
				/ (float) mStatFs.getBlockCount();
		mFreeSpacePct = (int) (percentage * 100);
		mStorageBarImageView = (BarImageView) findViewById(R.id.storage_barImageView);
		mStorageBarImageView.setPercentage(percentage);
		mStorageTextView = (TextView) findViewById(R.id.storage_text);
		mStorageSpacerTextView = (TextView) findViewById(R.id.storage_text_spacer);
		mStorageTextView.setText(mFreeSpacePct + "%");
		mStorageSpacerTextView.setPadding(mStorageSpacerTextView.getPaddingLeft(),
				(int) ((1 - percentage) * UI_BAR_MAX_TOP_PADDING), mStorageSpacerTextView.getPaddingRight(),
				mStorageSpacerTextView.getPaddingBottom());

		mDataDrawer = (SlidingDrawer) findViewById(R.id.dataDrawer);
		mDiagnosticsDrawer = (SlidingDrawer) findViewById(R.id.diagnosticsDrawer);

		final ImageButton recordButton = (ImageButton) findViewById(R.id.button_record);
		recordButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				synchronized (mIsRecording) {
					if (mIsRecording) {
                        recordButton.setImageResource(R.drawable.rec_button_up);
                        stopRecording();
					} else {
                        recordButton.setImageResource(R.drawable.rec_button_pressed);
                        startRecording();
                    }
				}
			}
		});

		final ImageButton dataButton = (ImageButton) findViewById(R.id.button_data);
		dataButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mDataDrawer.isOpened()) {
					dataButton.setImageResource(R.drawable.data_button_up);
					mDataDrawer.animateClose();
				} else {
					dataButton.setImageResource(R.drawable.data_button_pressed);
					mDataDrawer.animateOpen();
				}
			}
		});

		final ImageButton diagnosticsButton = (ImageButton) findViewById(R.id.button_diagnostics);
		diagnosticsButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mDiagnosticsDrawer.isOpened()) {
					diagnosticsButton.setImageResource(R.drawable.diagnostics_button_up);
					mDiagnosticsDrawer.animateClose();
				} else {
					diagnosticsButton.setImageResource(R.drawable.diagnostics_button_pressed);
					mDiagnosticsDrawer.animateOpen();
				}
			}
		});

		// setupSensors();
		initSensorUi();
	}

    @Override
	protected void onResume() {
		Log.i(TAG, "onResume");

		super.onResume();

        initSensors();

		NetworkHelper.startConfiguration(getApplicationContext());

		mRemoteControl = new RemoteControl(getApplicationContext());
		mRemoteControl.registerCommandListener("start", mCommandListener);
		mRemoteControl.registerCommandListener("stop", mCommandListener);
		mRemoteControl.registerCommandListener("status", mCommandListener);
		mRemoteControl.start();
	}

    @Override
	protected void onPause() {
		Log.i(TAG, "onPause");

		super.onPause();

		// Unregister sensor listeners
		for (Sensor s : mSensors) {
			mSensorManager.unregisterListener(mSensorEventListener, s);
		}

        // Does the gps cleanup/file closing
        mGpsManager.shutdown();
        cleanupEmptyFiles();

        synchronized (mIsRecording) {
            if (mIsRecording)
                stopRecording();
        }
        mCameraView.release();

		// Unregister battery
		unregisterReceiver(batteryBroadcastReceiver);

		// Unregister WiFi
		mWapManager.unregisterReceiver();

		// Stop the remote commanding
		mRemoteControl.shutdown();
		mRemoteControl = null;
	}

	@Override
	protected void onDestroy() {
		Log.i(TAG, "onDestroy");

		mCameraView.release();
		super.onDestroy();
	};

	@Override
	protected Dialog onCreateDialog(int id) {
		if (id != PROGRESS_ID) {
			return super.onCreateDialog(id);
		}
		final ProgressDialog progressDialog = new ProgressDialog(this);
		progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCancelable(false);

		// The setMessage call must be in both onCreateDialog and
		// onPrepareDialog otherwise it will
		// fail to update the dialog in onPrepareDialog.
        progressDialog.setMessage("Processing...");

		return progressDialog;
	}

	@Override
	public void onPrepareDialog(int id, Dialog dialog, Bundle bundle) {
		super.onPrepareDialog(id, dialog, bundle);

		if (id != PROGRESS_ID) {
			return;
		}

		final ProgressDialog progressDialog = (ProgressDialog) dialog;
		progressDialog.setMessage("Processing...");
		final Handler handler = new Handler() {
			@Override
			public void handleMessage(android.os.Message msg) {
				int done = msg.getData().getInt("percentageDone");
				String status = msg.getData().getString("status");
				progressDialog.setProgress(done);
				progressDialog.setMessage(status);

				mRemoteControl.broadcastMessage("Zipping Progress: " + done + "%\n");
			}
		};

		new Thread() {
			@Override
			public void run() {
				if (mUseZip) {
					ZipItUpRequest request = new ZipItUpRequest();
					String directoryName = mApp.getLoggerPathPrefix();
					request.setInputFiles(new FileListFetcher().getFilesAndDirectoriesInDir(directoryName));
					request.setOutputFile(directoryName + "/logged-data.zip");
					request.setMaxOutputFileSize(MAX_OUTPUT_ZIP_CHUNK_SIZE);
					request.setDeleteInputfiles(true);
					request.setCompressionLevel(Deflater.NO_COMPRESSION);

					try {
						new ZipItUpProcessor(request).chunkIt(handler);
					} catch (IOException e) {
						Log.e("Oh Crap!", "IoEx", e);
					}
				}
				// closing dialog
				progressDialog.dismiss();
				mApp.generateNewFilePathUniqueIdentifier();

				// TODO: Need to deal with empty directories that are created if
				// another recording
				// session is never started.
				createSensorLogFiles();

				try {
                    mRemoteControl.broadcastMessage("*** Packaging Finished: OK to start ***\n");
				} catch (RuntimeException e) {
					e.printStackTrace();
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							Toast.makeText(getApplicationContext(),
									"Camera hardware error. Please restart the application.", Toast.LENGTH_LONG)
									.show();
						}
					});
					finish();
					return;
				}
			}
		}.start();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			synchronized (mIsRecording) {
                if (mIsRecording)
                    return true;
			}
		}
		return super.onKeyDown(keyCode, event);
	}

	private void createSensorLogFiles() {
        mApp.generateNewFilePathUniqueIdentifier();
        mApp.createDirectoryIfNotExisted(mApp.getDataLoggerPath());

		mSensorLogFileWriters = new HashMap<String, BufferedWriter>();

		for (Sensor s : mSensors)
            createBufferedWriter(s.getName());

		// The battery is a special case since it is not a real sensor
		mBatteryTempWriter = createBufferedWriter("BatteryTemp");
		mBatteryLevelWriter = createBufferedWriter("BatteryLevel");
		mBatteryVoltageWriter = createBufferedWriter("BatteryVoltage");

		// GPS is another special case since it is not a real sensor
		mGpsLocationWriter = createBufferedWriter("GpsLocation");
		mGpsStatusWriter = createBufferedWriter("GpsStatus");
		mGpsNmeaWriter = createBufferedWriter("GpsNmea");

		// Wifi is another special case
		mWifiWriter = createBufferedWriter("Wifi");
	}

    private void closeSensorLogFiles() {
        try {
            Collection<BufferedWriter> writers = mSensorLogFileWriters.values();
            for (BufferedWriter w : writers)
                w.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startRecording() {
        createSensorLogFiles();

        mStartRecTime = System.currentTimeMillis();
        new Thread(updateRecTimeDisplay).start();
        if (mMode == MODE_VIDEO) {
            try {
                mCameraView.recordVideo(mApp.getVideoFilepath());
                mRemoteControl.broadcastMessage("*** Recording Started ***\n");
            } catch (Exception e) {
                Log.e(TAG, "Recording has failed...", e);
                Toast.makeText(getApplicationContext(),
                        "Camera hardware error. Please restart the application.", Toast.LENGTH_LONG)
                        .show();
                finish();
            }
        } else {
            try {
                mCameraView.takePictures(mDelay);
                mRemoteControl.broadcastMessage("*** Recording Started ***\n");
            } catch (Exception e) {
                Log.e(TAG, "Taking pictures has failed...", e);
                Toast.makeText(getApplicationContext(),
                        "Taking pictures is not possible at the moment: " + e.toString(),
                        Toast.LENGTH_SHORT).show();
            }
        }
        mIsRecording = true;
    }

    private void stopRecording() {
        mCameraView.stopRecording();
        mIsRecording = false;
        mStartRecTime = 0;

        closeSensorLogFiles();

        mRemoteControl.broadcastMessage("*** Recording Stopped ***\n");
    }

	/**
	 * Creates a new BufferedWriter.
	 *
	 * @param prefix
	 *            The prefix for the file that we're writing to.
	 * @return A BufferedWriter for a file in the specified directory. Null if
	 *         creation failed.
	 */
	private BufferedWriter createBufferedWriter(String prefix) {
		String filename = mApp.generateDataFilePath(prefix);
		File file = new File(filename);
		try {
			BufferedWriter writer = new BufferedWriter(new FileWriter(file));
            mSensorLogFileWriters.put(prefix, writer);
            return writer;
		} catch (IOException e) {
			e.printStackTrace();
            return null;
		}
	}

    private void initSensorUi() {
        mAccelXTextView = (TextView) findViewById(R.id.accelerometerX_text);
        mAccelYTextView = (TextView) findViewById(R.id.accelerometerY_text);
        mAccelZTextView = (TextView) findViewById(R.id.accelerometerZ_text);

        mGyroXTextView = (TextView) findViewById(R.id.gyroX_text);
        mGyroYTextView = (TextView) findViewById(R.id.gyroY_text);
        mGyroZTextView = (TextView) findViewById(R.id.gyroZ_text);

        mMagXTextView = (TextView) findViewById(R.id.magneticFieldX_text);
        mMagYTextView = (TextView) findViewById(R.id.magneticFieldY_text);
        mMagZTextView = (TextView) findViewById(R.id.magneticFieldZ_text);

        mBatteryTempBarImageView = (BarImageView) findViewById(R.id.temperature_barImageView);
        mBatteryTempTextView = (TextView) findViewById(R.id.batteryTemp_text);
        mBatteryTempSpacerTextView = (TextView) findViewById(R.id.batteryTemp_text_spacer);
    }

	private void updateSensorUi(int sensorType, int accuracy, float[] values) {
		// IMPORTANT: DO NOT UPDATE THE CONTENTS INSIDE A DRAWER IF IT IS BEING
		// ANIMATED VIA A CALL TO animateOpen/animateClose!!!
		// Updating anything inside will stop the animation from running.
		// Note that this does not seem to affect the animation if it had been
		// triggered by dragging the drawer instead of being called
		// programatically.
		if (mDataDrawer.isMoving()) {
			return;
		}

		TextView xTextView;
		TextView yTextView;
		TextView zTextView;
		if (sensorType == Sensor.TYPE_ACCELEROMETER) {
			xTextView = mAccelXTextView;
			yTextView = mAccelYTextView;
			zTextView = mAccelZTextView;
		} else if (sensorType == Sensor.TYPE_GYROSCOPE) {
			xTextView = mGyroXTextView;
			yTextView = mGyroYTextView;
			zTextView = mGyroZTextView;
		} else if (sensorType == Sensor.TYPE_MAGNETIC_FIELD) {
			xTextView = mMagXTextView;
			yTextView = mMagYTextView;
			zTextView = mMagZTextView;
		} else {
			return;
		}

		int textColor = Color.WHITE;
		String prefix = "";
		switch (accuracy) {
		case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
			prefix = "  ";
			break;
		case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
			prefix = "  *";
			break;
		case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
			prefix = "  **";
			break;
		case SensorManager.SENSOR_STATUS_UNRELIABLE:
			prefix = "  ***";
			break;
		}

		xTextView.setTextColor(textColor);
		yTextView.setTextColor(textColor);
		zTextView.setTextColor(textColor);
		xTextView.setText(prefix + numberDisplayFormatter(values[0]));
		yTextView.setText(prefix + numberDisplayFormatter(values[1]));
		zTextView.setText(prefix + numberDisplayFormatter(values[2]));
	}

    private void initSensors() {
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : mSensors) {
            Log.d(TAG, "Setup sensor: " + s.getName());
            mSensorManager.registerListener(mSensorEventListener, s, SensorManager.SENSOR_DELAY_GAME);
        }
        initBattery();
        initGps();
        initWifi();
    }

	private void initGps() {
		mGpsManager = new GpsManager(this, new GpsManagerListener() {

			@Override
			public void onGpsLocationUpdate(long time, float accuracy, double latitude, double longitude,
					double altitude, float bearing, float speed) {

                mGpsLocationView.setText("Lat: " + latitude + "\nLon: " + longitude);

				synchronized (mIsRecording) {
					if (!mIsRecording)
						return;
				}

                try {
                    mGpsLocationWriter.write(time + "," + accuracy + "," + latitude + "," + longitude + "," + altitude
                            + "," + bearing + "," + speed + "\n");
                } catch (IOException e) {
                    e.printStackTrace();
                }
			}

			@Override
			public void onGpsNmeaUpdate(long time, String nmeaString) {
				synchronized (mIsRecording) {
					if (!mIsRecording)
						return;
				}

				try {
					mGpsNmeaWriter.write(time + "," + nmeaString + "\n");
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			@Override
			public void onGpsStatusUpdate(long time, int maxSatellites, int actualSatellites, int timeToFirstFix) {
				synchronized (mIsRecording) {
					if (!mIsRecording)
						return;
				}

				try {
					mGpsStatusWriter.write(time + "," + maxSatellites + "," + actualSatellites + "," + timeToFirstFix + "\n");
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
	}

	private void initWifi() {
		if (mWapManager == null)
			mWapManager = new WapManager(getApplicationContext(), mWifiListener);
        mWapManager.registerReceiver();
	}

	private void initBattery() {
        // Battery isn't a regular sensor; instead we have to use a Broadcast
        // receiver.
        //
        // We always write this file since the battery changed event isn't
        // called that often; otherwise, we might miss the initial battery
        // reading.
        //
        // Note that we are reading the current time in MILLISECONDS for this,
        // as opposed to NANOSECONDS for regular mSensors.
		IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryBroadcastReceiver, batteryFilter);
	}

	private String numberDisplayFormatter(float value) {
		String displayedText = Float.toString(value);
		if (value >= 0) {
			displayedText = " " + displayedText;
		}
		if (displayedText.length() > 8) {
			displayedText = displayedText.substring(0, 8);
		}
		while (displayedText.length() < 8) {
			displayedText = displayedText + " ";
		}
		return displayedText;
	}

	private void cleanupEmptyFiles() {
		Log.i(TAG, "cleaning up empty dirs and zero byte files");
		String logPath = mApp.getLoggerPathPrefix();
		List<String> filesAndDirs = new FileListFetcher().getFilesAndDirectoriesInDir(logPath);
		List<String> allFilesAndDir = new ArrayList<String>(filesAndDirs.size() + 1);
		allFilesAndDir.addAll(filesAndDirs);
		allFilesAndDir.add(logPath);

		// make sure that all files in this list are zero byte files
		for (String name : allFilesAndDir) {
			File f = new File(name);
			if (f.isFile() && f.length() != 0) {
				// encountered a non-zero length file, abort deletes
				Log.i(TAG, "File: " + name + " has length: " + f.length() + "; aborting cleanup");
				return;
			}
		}

		// delete all files and dirs
		boolean atLeastOneFileWasDeleted = true;
		while (atLeastOneFileWasDeleted) {
			atLeastOneFileWasDeleted = false;
			for (String name : allFilesAndDir) {
				File f = new File(name);
				if (f.exists() && f.delete()) {
					Log.d(TAG, "deleted " + name);
					atLeastOneFileWasDeleted = true;
				}
			}
		}
	}
}
