package org.snurkle.badgeturntable;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import org.opencv.android.OpenCVLoader;
import org.snurkle.badgeturntable.rig.Station;
import org.snurkle.badgeturntable.ui.CaptureActivity;

public final class App extends Application {
    private Station station;

    /** The app's one {@link Station}: jobs, sessions, shots, the capture target and the server. */
    public static Station station(Context context) {
        return ((App) context.getApplicationContext()).station;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!OpenCVLoader.initLocal()) {
            Log.e("App", "OpenCV failed to load; the encoder ring won't be read");
        }
        station = new Station(this);
        // The editor's POST /api/camera/open. The server only runs in the foreground, so the
        // app is allowed to start the screen.
        station.setCameraLauncher(() -> startActivity(new Intent(this, CaptureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        // The remote-control server runs while any of the app's screens is in the foreground,
        // so moving between screens doesn't drop the editor.
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                station.setForeground(true);
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                station.setForeground(false);
            }
        });
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
