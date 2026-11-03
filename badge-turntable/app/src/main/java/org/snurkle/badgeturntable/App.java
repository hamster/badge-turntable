package org.snurkle.badgeturntable;

import android.app.Application;
import android.util.Log;

import org.opencv.android.OpenCVLoader;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (!OpenCVLoader.initLocal()) {
            Log.e("App", "OpenCV failed to load; the encoder ring won't be read");
        }
    }
}
