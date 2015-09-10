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

import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import com.cellbots.logger.localServer.ServerControlActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * A simple Activity for choosing which mode to launch the data logger in.
 * 
 * @author clchen@google.com (Charles L. Chen)
 */
public class LauncherActivity extends Activity {

    private static final String TAG = "LauncherActivity";

    @Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		final Button launchLocalServerButton = (Button) findViewById(R.id.launchLocalServer);
		launchLocalServerButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(LauncherActivity.this, ServerControlActivity.class);
				startActivity(i);
			}
		});

        // camera settings
        Spinner camSpin = (Spinner) findViewById(R.id.camSpin);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.camera_names, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        camSpin.setAdapter(adapter);
        camSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // generate items for preview resolution spinner
                Camera cam = Camera.open(position);
                Camera.Parameters params = cam.getParameters();

                generateItemsForSpinner((Spinner) findViewById(R.id.videoResSpin), params.getPreferredPreviewSizeForVideo(), params.getSupportedVideoSizes());
                generateItemsForSpinner((Spinner) findViewById(R.id.picResSpin), params.getPictureSize(), params.getSupportedPictureSizes());

                cam.release();
            }
            private void generateItemsForSpinner(Spinner spin, Camera.Size preferredSize, List<Camera.Size> supportedSizes) {
                Log.d(TAG, "Preferred size: " + preferredSize.width + "X" + preferredSize.height);

                List<CharSequence> spinnerItems = new ArrayList<CharSequence>();
                int preferredIdx = -1;
                int i = 0;
                int minDist = Integer.MAX_VALUE;
                for (Camera.Size sz : supportedSizes) {
                    int dist = Math.abs(sz.width * sz.height - preferredSize.width * preferredSize.height);
                    if (dist <= minDist) {
                        preferredIdx = i;
                        minDist = dist;
                    }
                    ++i;
                    spinnerItems.add(sz.width + "X" + sz.height);
                }
                ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(spin.getContext(),
                        android.R.layout.simple_spinner_item, spinnerItems);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spin.setAdapter(adapter);
                spin.setSelection(preferredIdx);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

		final Button launchVideoBackButton = (Button) findViewById(R.id.launchVideo);
		launchVideoBackButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				launchLoggingActivity(LoggerActivity.MODE_VIDEO);
			}
		});

		final Button launchPictureButton = (Button) findViewById(R.id.launchPicture);
		launchPictureButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
                launchLoggingActivity(LoggerActivity.MODE_PICTURES);
			}
		});
	}

	private void launchLoggingActivity(int mode) {
        final CheckBox useZipCheckbox = (CheckBox) findViewById(R.id.useZip);
        final Spinner camSpin = (Spinner) findViewById(R.id.camSpin);
        final Spinner resSpin = mode == LoggerActivity.MODE_PICTURES ? (Spinner) findViewById(R.id.picResSpin) : (Spinner) findViewById(R.id.videoResSpin);

        Intent i = new Intent(LauncherActivity.this, LoggerActivity.class);
		i.putExtra(LoggerActivity.EXTRA_MODE, mode);
        i.putExtra(LoggerActivity.EXTRA_CAMERA, camSpin.getSelectedItemPosition());
        i.putExtra(LoggerActivity.EXTRA_CAMERA_RESOLUTION, resSpin.getSelectedItem().toString());
		i.putExtra(LoggerActivity.EXTRA_USE_ZIP, useZipCheckbox.isChecked());

        if (mode == LoggerActivity.MODE_PICTURES) {
            int delay = 30;
            try {
                final EditText pictureDelayEditText = (EditText) findViewById(R.id.pictureDelay);
                delay = Integer.parseInt(pictureDelayEditText.getText().toString());
            } catch (Exception e) {
                Toast.makeText(LauncherActivity.this,
                        "Error parsing picture delay time. Using default delay of 30 seconds.",
                        Toast.LENGTH_LONG).show();
            }
            i.putExtra(LoggerActivity.EXTRA_PICTURE_DELAY, delay);
        }
		startActivity(i);
	}
}
