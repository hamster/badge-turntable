package org.snurkle.badgeturntable.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.motion.Calibration;
import org.snurkle.badgeturntable.sync.SecretStore;
import org.snurkle.badgeturntable.sync.SmbClient;
import org.snurkle.badgeturntable.sync.SmbSettings;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;

public final class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(R.id.settings, new SettingsFragment()).commit();
        }
    }

    public static final class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
            SecretStore secrets = new SecretStore(requireContext());

            // The password is never stored in plain preferences: it goes to the Keystore-backed store.
            EditTextPreference password = findPreference("smb_password_input");
            if (password != null) {
                password.setOnBindEditTextListener(e ->
                        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                password.setSummary(secrets.get(SmbSettings.SECRET_PASSWORD) == null ? R.string.not_set : R.string.set);
                password.setOnPreferenceChangeListener((p, value) -> {
                    secrets.put(SmbSettings.SECRET_PASSWORD, value.toString());
                    p.setSummary(R.string.set);
                    return false;  // don't persist the plain text
                });
            }

            EditTextPreference port = findPreference(SmbSettings.KEY_PORT);
            if (port != null) port.setOnBindEditTextListener(e -> e.setInputType(InputType.TYPE_CLASS_NUMBER));

            Preference test = findPreference("smb_test");
            if (test != null) test.setOnPreferenceClickListener(p -> {
                p.setSummary(R.string.testing);
                Executors.newSingleThreadExecutor().execute(() -> {
                    List<String> lines = SmbClient.test(SmbSettings.load(requireContext()));
                    requireActivity().runOnUiThread(() -> {
                        p.setSummary(null);
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.smb_test)
                                .setMessage(String.join("\n", lines))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    });
                });
                return true;
            });

            Preference cal = findPreference("calibration");
            if (cal != null) {
                Calibration c = Calibration.load(requireContext());
                cal.setSummary(c == null ? getString(R.string.not_calibrated)
                        : getString(R.string.calibrated_summary, c.degPerSec, c.stopOvershootDeg,
                        DateFormat.getDateTimeInstance().format(new Date(c.calibratedAtMs))));
                cal.setOnPreferenceClickListener(p -> {
                    Calibration.clear(requireContext());
                    p.setSummary(R.string.not_calibrated);
                    return true;
                });
            }

            Preference probe = findPreference("probe");
            if (probe != null) probe.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(requireContext(), ProbeActivity.class));
                return true;
            });
        }
    }
}
