/*
 * Copyright 2016, Optimizely
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.optimizely.ab.android.sdk;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RawRes;

import com.optimizely.ab.Optimizely;
import com.optimizely.ab.android.event_handler.OptlyEventHandler;
import com.optimizely.ab.android.shared.ServiceScheduler;
import com.optimizely.ab.bucketing.UserExperimentRecord;
import com.optimizely.ab.config.parser.ConfigParseException;
import com.optimizely.ab.event.internal.payload.Event;
import com.optimizely.user_experiment_record.AndroidUserExperimentRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Handles loading the Optimizely data file
 */
public class OptimizelyManager {
    @NonNull private static AndroidOptimizely androidOptimizely = new AndroidOptimizely(null);
    @NonNull private final String projectId;
    @NonNull private final Long eventHandlerDispatchInterval;
    @NonNull private final TimeUnit eventHandlerDispatchIntervalTimeUnit;
    @NonNull private final Long dataFileDownloadInterval;
    @NonNull private final TimeUnit dataFileDownloadIntervalTimeUnit;
    @NonNull private final Executor executor;
    @NonNull private final Logger logger;
    @Nullable private DataFileServiceConnection dataFileServiceConnection;
    @Nullable private OptimizelyStartListener optimizelyStartListener;

    OptimizelyManager(@NonNull String projectId,
                      @NonNull Long eventHandlerDispatchInterval,
                      @NonNull TimeUnit eventHandlerDispatchIntervalTimeUnit,
                      @NonNull Long dataFileDownloadInterval,
                      @NonNull TimeUnit dataFileDownloadIntervalTimeUnit,
                      @NonNull Executor executor,
                      @NonNull Logger logger) {
        this.projectId = projectId;
        this.eventHandlerDispatchInterval = eventHandlerDispatchInterval;
        this.eventHandlerDispatchIntervalTimeUnit = eventHandlerDispatchIntervalTimeUnit;
        this.dataFileDownloadInterval = dataFileDownloadInterval;
        this.dataFileDownloadIntervalTimeUnit = dataFileDownloadIntervalTimeUnit;
        this.executor = executor;
        this.logger = logger;
    }

    @NonNull
    public static Builder builder(@NonNull String projectId) {
        return new Builder(projectId);
    }

    @Nullable
    DataFileServiceConnection getDataFileServiceConnection() {
        return dataFileServiceConnection;
    }

    void setDataFileServiceConnection(@Nullable DataFileServiceConnection dataFileServiceConnection) {
        this.dataFileServiceConnection = dataFileServiceConnection;
    }

    @Nullable
    OptimizelyStartListener getOptimizelyStartListener() {
        return optimizelyStartListener;
    }

    void setOptimizelyStartListener(@Nullable OptimizelyStartListener optimizelyStartListener) {
        this.optimizelyStartListener = optimizelyStartListener;
    }

    public void start(@NonNull Activity activity, @NonNull OptimizelyStartListener optimizelyStartListener) {
        activity.getApplication().registerActivityLifecycleCallbacks(new OptlyActivityLifecycleCallbacks(this));
        start(activity.getApplication(), optimizelyStartListener);
    }

    public void start(@NonNull Context context, @NonNull OptimizelyStartListener optimizelyStartListener) {
        this.optimizelyStartListener = optimizelyStartListener;
        this.dataFileServiceConnection = new DataFileServiceConnection(this);
        final Intent intent = new Intent(context.getApplicationContext(), DataFileService.class);
        context.getApplicationContext().bindService(intent, dataFileServiceConnection, Context.BIND_AUTO_CREATE);
    }

    void stop(@NonNull Activity activity, @NonNull OptlyActivityLifecycleCallbacks optlyActivityLifecycleCallbacks) {
        stop(activity);
        activity.getApplication().unregisterActivityLifecycleCallbacks(optlyActivityLifecycleCallbacks);
    }

    public void stop(@NonNull Context context) {
        if (dataFileServiceConnection != null && dataFileServiceConnection.isBound()) {
            context.getApplicationContext().unbindService(dataFileServiceConnection);
        }

        this.optimizelyStartListener = null;
    }

    @NonNull
    public AndroidOptimizely getOptimizely() {
        return androidOptimizely;
    }

    @NonNull
    public AndroidOptimizely getOptimizely(@NonNull Context context, @RawRes int dataFileRes) {
        AndroidUserExperimentRecord userExperimentRecord =
                (AndroidUserExperimentRecord) AndroidUserExperimentRecord.newInstance(getProjectId(), context);
        // Blocking File I/O is necessary here in order to provide a synchronous API
        // The User Experiment Record is started off the of the main thread when starting
        // asynchronously.  Starting simply creates the file if it doesn't exist so it's not
        // terribly expensive. Blocking the UI the thread prevents touch input...
        userExperimentRecord.start();
        try {
            androidOptimizely = buildOptimizely(context, loadRawResource(context, dataFileRes), userExperimentRecord);
        } catch (ConfigParseException e) {
            logger.error("Unable to parse compiled data file", e);
        } catch (IOException e) {
            logger.error("Unable to load compiled data file", e);
        }

        return androidOptimizely;
    }

    private String loadRawResource(Context context, @RawRes int rawRes) throws IOException {
        Resources res = context.getResources();
        InputStream in = res.openRawResource(rawRes);
        byte[] b = new byte[in.available()];
        int read = in.read(b);
        if (read > -1) {
            return new String(b);
        } else {
            throw new IOException("Couldn't parse raw res fixture, no bytes");
        }
    }

    @NonNull
    public String getProjectId() {
        return projectId;
    }

    void injectOptimizely(@NonNull final Context context, final @NonNull AndroidUserExperimentRecord userExperimentRecord, @NonNull final ServiceScheduler serviceScheduler, @NonNull final String dataFile) {
        AsyncTask<Void, Void, UserExperimentRecord> initUserExperimentRecordTask = new AsyncTask<Void, Void, UserExperimentRecord>() {
            @Override
            protected UserExperimentRecord doInBackground(Void[] params) {
                userExperimentRecord.start();
                return userExperimentRecord;
            }

            @Override
            protected void onPostExecute(UserExperimentRecord userExperimentRecord) {
                Intent intent = new Intent(context, DataFileService.class);
                intent.putExtra(DataFileService.EXTRA_PROJECT_ID, projectId);
                serviceScheduler.schedule(intent, dataFileDownloadIntervalTimeUnit.toMillis(dataFileDownloadInterval));

                try {
                    OptimizelyManager.androidOptimizely = buildOptimizely(context, dataFile, userExperimentRecord);
                    logger.info("Sending Optimizely instance to listener");

                    if (optimizelyStartListener != null) {
                        optimizelyStartListener.onStart(androidOptimizely);
                        // Prevent the onOptimizelyStarted(AndroidOptimizely) callback from being hit twice
                        // This could happen if the local data file is not null and is different
                        // from the remote data file.  Setting the listener to null handles this case.
                        optimizelyStartListener = null;
                    } else {
                        logger.info("No listener to send Optimizely to");
                    }
                } catch (ConfigParseException e) {
                    logger.error("Unable to build optimizely instance", e);
                }
            }
        };
        initUserExperimentRecordTask.executeOnExecutor(executor);
    }

    private AndroidOptimizely buildOptimizely(@NonNull Context context, @NonNull String dataFile, @NonNull UserExperimentRecord userExperimentRecord) throws ConfigParseException {
        OptlyEventHandler eventHandler = OptlyEventHandler.getInstance(context);
        eventHandler.setDispatchInterval(eventHandlerDispatchInterval, eventHandlerDispatchIntervalTimeUnit);
        Optimizely optimizely = Optimizely.builder(dataFile, eventHandler)
                .withUserExperimentRecord(userExperimentRecord)
                .withClientEngine(Event.ClientEngine.ANDROID_SDK)
                .withClientVersion(BuildConfig.CLIENT_VERSION)
                .build();
        return new AndroidOptimizely(optimizely);
    }

    static class OptlyActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {

        @NonNull private OptimizelyManager optimizelyManager;

        OptlyActivityLifecycleCallbacks(@NonNull OptimizelyManager optimizelyManager) {
            this.optimizelyManager = optimizelyManager;
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            // NO-OP
        }

        @Override
        public void onActivityStarted(Activity activity) {
            // NO-OP
        }

        @Override
        public void onActivityResumed(Activity activity) {
            // NO-OP
        }

        @Override
        public void onActivityPaused(Activity activity) {
            // NO-OP
        }

        @Override
        public void onActivityStopped(Activity activity) {
            optimizelyManager.stop(activity, this);
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            // NO-OP
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            // NO-OP
        }
    }

    static class DataFileServiceConnection implements ServiceConnection {

        @NonNull private final OptimizelyManager optimizelyManager;
        private boolean bound = false;

        DataFileServiceConnection(@NonNull OptimizelyManager optimizelyManager) {
            this.optimizelyManager = optimizelyManager;
        }

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to DataFileService, cast the IBinder and get DataFileService instance
            DataFileService.LocalBinder binder = (DataFileService.LocalBinder) service;
            final DataFileService dataFileService = binder.getService();
            if (dataFileService != null) {
                DataFileLoader dataFileLoader = new DataFileLoader(new DataFileLoader.TaskChain(dataFileService),
                        LoggerFactory.getLogger(DataFileLoader.class));
                dataFileService.getDataFile(optimizelyManager.getProjectId(), dataFileLoader, new DataFileLoadedListener() {
                    @Override
                    public void onDataFileLoaded(String dataFile) {
                        if (bound) {
                            AlarmManager alarmManager = (AlarmManager) dataFileService.getApplicationContext().getSystemService(Context.ALARM_SERVICE);
                            ServiceScheduler.PendingIntentFactory pendingIntentFactory = new ServiceScheduler.PendingIntentFactory(dataFileService.getApplicationContext());
                            ServiceScheduler serviceScheduler = new ServiceScheduler(alarmManager, pendingIntentFactory, LoggerFactory.getLogger(ServiceScheduler.class));
                            AndroidUserExperimentRecord userExperimentRecord =
                                    (AndroidUserExperimentRecord) AndroidUserExperimentRecord.newInstance(optimizelyManager.getProjectId(), dataFileService.getApplicationContext());
                            optimizelyManager.injectOptimizely(dataFileService.getApplicationContext(), userExperimentRecord, serviceScheduler, dataFile);
                        }
                    }
                });
            }
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            bound = false;
        }

        boolean isBound() {
            return bound;
        }

        void setBound(boolean bound) {
            this.bound = bound;
        }
    }

    @SuppressWarnings("WeakerAccess")
    public static class Builder {

        @NonNull private final String projectId;

        @NonNull private Long dataFileDownloadInterval = 1L;
        @NonNull private TimeUnit dataFileDownloadIntervalTimeUnit = TimeUnit.DAYS;
        @NonNull private Long eventHandlerDispatchInterval = 1L;
        @NonNull private TimeUnit eventHandlerDispatchIntervalTimeUnit = TimeUnit.DAYS;

        Builder(@NonNull String projectId) {
            this.projectId = projectId;
        }

        public Builder withEventHandlerDispatchInterval(long interval, @NonNull TimeUnit timeUnit) {
            this.eventHandlerDispatchInterval = interval;
            this.eventHandlerDispatchIntervalTimeUnit = timeUnit;
            return this;
        }

        public Builder withDataFileDownloadInterval(long interval, @NonNull TimeUnit timeUnit) {
            this.dataFileDownloadInterval = interval;
            this.dataFileDownloadIntervalTimeUnit = timeUnit;
            return this;
        }

        public OptimizelyManager build() {
            final Logger logger = LoggerFactory.getLogger(OptimizelyManager.class);

            return new OptimizelyManager(projectId,
                    eventHandlerDispatchInterval,
                    eventHandlerDispatchIntervalTimeUnit,
                    dataFileDownloadInterval,
                    dataFileDownloadIntervalTimeUnit,
                    Executors.newSingleThreadExecutor(),
                    logger);

        }
    }
}
