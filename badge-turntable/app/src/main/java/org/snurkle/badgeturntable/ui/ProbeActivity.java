package org.snurkle.badgeturntable.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.camera.CapabilitiesProbe;

import java.util.concurrent.Executors;

/** Shows what the phone allows (cameras, manual controls, IR). Tap the text to copy it. */
public final class ProbeActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_probe);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView out = findViewById(R.id.report);
        Executors.newSingleThreadExecutor().execute(() -> {
            String report = CapabilitiesProbe.report(this);
            runOnUiThread(() -> out.setText(report));
        });
        out.setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("Capabilities", out.getText()));
        });
    }
}
