package org.snurkle.badgeturntable;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.opencv.android.OpenCVLoader;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (!OpenCVLoader.initLocal()) {
            Log.e("App", "OpenCV failed to load; the encoder ring won't be read");
        }
        // The phone sits on a tripod at the light box, often untouched for minutes, so keep
        // the screen on whenever one of our screens is showing. The flag only applies while
        // the window is visible, so it doesn't hold the phone awake in the background.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            @Override public void onActivityStarted(@NonNull Activity activity) {}
            @Override public void onActivityResumed(@NonNull Activity activity) {}
            @Override public void onActivityPaused(@NonNull Activity activity) {}
            @Override public void onActivityStopped(@NonNull Activity activity) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
            @Override public void onActivityDestroyed(@NonNull Activity activity) {}
        });
    }
}
