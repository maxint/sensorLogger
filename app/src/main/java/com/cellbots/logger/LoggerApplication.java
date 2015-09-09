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

import android.app.Application;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.SimpleTimeZone;

public class LoggerApplication extends Application {
    /**
     * A date value is used as a unique identifier for file paths.
     */
    private String mFilePathUniqueIdentifier;

    @Override
    public void onCreate() {
        super.onCreate();
        generateNewFilePathUniqueIdentifier();
    }

    public void generateNewFilePathUniqueIdentifier() {
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat();
        sdf.setTimeZone(new SimpleTimeZone(0, "GMT"));
        sdf.applyPattern("dd MMM yyyy HH:mm:ss z");
        mFilePathUniqueIdentifier = sdf.format(date).replaceAll(" ", "_").replaceAll(":", "-");
    }

    /**
     * Returns the filePathUniqueIdentifier that can be used for saving files.
     * 
     * @throw IllegalStateException if the filePathUniqueIdentifier hasn't been
     *        initialized.
     */
    private String getFilePathUniqueIdentifier() {
        if (mFilePathUniqueIdentifier == null) {
            throw new IllegalStateException("filePathUniqueIdentifier has not been initialized for the app.");
        }
        return mFilePathUniqueIdentifier;
    }

    public String generateDataFilePath(String prefix) {
        return getDataLoggerPath() + "/" + prefix.replaceAll(" ", "_") + "_" + getFilePathUniqueIdentifier() + ".txt";
    }

    public String getLoggerPathPrefix() {
        return Environment.getExternalStorageDirectory() + "/SmartphoneLoggerData/" + getFilePathUniqueIdentifier();
    }

    public String getDataLoggerPath() {
        return getLoggerPathPrefix() + "/data";
    }

    public void createDirectoryIfNotExisted(String dirName) {
        File directory = new File(dirName);
        if (!directory.exists())
            directory.mkdirs();
    }

    public String getVideoFilepath() {
        return getLoggerPathPrefix() + "/video-" + mFilePathUniqueIdentifier + ".mp4";
    }

    public String getPicturesDirectoryPath() {
        return getLoggerPathPrefix() + "/pictures";
    }
}
