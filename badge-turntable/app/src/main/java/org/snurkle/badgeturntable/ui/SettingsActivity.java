package org.snurkle.badgeturntable.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.ir.IrCode;
import org.snurkle.badgeturntable.ir.IrTransmitter;
import org.snurkle.badgeturntable.ir.Presets;
import org.snurkle.badgeturntable.ir.TurntableProfile;
import org.snurkle.badgeturntable.motion.Calibration;
import org.snurkle.badgeturntable.remote.Pairing;
import org.snurkle.badgeturntable.remote.RemoteServer;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends AppCompatActivity {
    private RemoteBanner banner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        banner = new RemoteBanner(this, findViewById(R.id.remote_banner));
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(R.id.settings, new SettingsFragment()).commit();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        banner.start();
    }

    @Override
    protected void onStop() {
        banner.stop();
        super.onStop();
    }

    public static final class SettingsFragment extends PreferenceFragmentCompat {
        /** One thread, so test presses go out in the order they were tapped. */
        private final ExecutorService irExecutor = Executors.newSingleThreadExecutor();

        @Override
        public void onDestroy() {
            super.onDestroy();
            irExecutor.shutdown();
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            EditTextPreference remotePort = findPreference("remote_port");
            if (remotePort != null) remotePort.setOnBindEditTextListener(e -> e.setInputType(InputType.TYPE_CLASS_NUMBER));
            populatePaired();

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

            Preference model = findPreference("turntable_model");
            if (model != null) model.setOnPreferenceChangeListener((p, value) -> {
                populateIrTests(value.toString());
                return true;
            });
            populateIrTests(getPreferenceManager().getSharedPreferences()
                    .getString("turntable_model", Presets.JAYEGT_574));
        }

        /** One row per paired PC. Tapping a row revokes it. */
        private void populatePaired() {
            PreferenceCategory cat = findPreference("remote_paired");
            if (cat == null) return;
            cat.removeAll();
            List<Pairing.Client> clients = RemoteServer.pairedClients(requireContext());
            if (clients.isEmpty()) {
                Preference none = new Preference(requireContext());
                none.setTitle(R.string.remote_paired_none);
                none.setSelectable(false);
                cat.addPreference(none);
                return;
            }
            for (Pairing.Client c : clients) {
                Preference row = new Preference(requireContext());
                row.setTitle(c.clientName);
                row.setSummary(getString(R.string.remote_paired_summary,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(c.pairedAtMs))));
                row.setOnPreferenceClickListener(p -> {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.remote_revoke_title, c.clientName))
                            .setMessage(R.string.remote_revoke_message)
                            .setPositiveButton(R.string.revoke, (d, w) -> {
                                RemoteServer.revoke(requireContext(), c.tokenHash);
                                populatePaired();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return true;
                });
                cat.addPreference(row);
            }
        }

        /** One row per role the profile has a code for. Tapping a row sends that code once. */
        private void populateIrTests(String profileId) {
            PreferenceCategory cat = findPreference("ir_test");
            if (cat == null) return;
            cat.removeAll();
            IrTransmitter ir = new IrTransmitter(requireContext());
            TurntableProfile profile = Presets.byId(requireContext(), profileId);
            if (!ir.available() || profile.codes().isEmpty()) {
                Preference none = new Preference(requireContext());
                none.setTitle(!ir.available() ? R.string.ir_no_blaster : R.string.ir_no_codes);
                none.setSelectable(false);
                cat.addPreference(none);
                return;
            }
            for (Map.Entry<TurntableProfile.Role, IrCode> e : profile.codes().entrySet()) {
                IrCode code = e.getValue();
                Preference row = new Preference(requireContext());
                row.setTitle(roleLabel(e.getKey()));
                row.setSummary(R.string.ir_test_summary);
                row.setOnPreferenceClickListener(p -> {
                    irExecutor.execute(() -> {
                        String result;
                        try {
                            ir.send(code);
                            result = getString(R.string.ir_sent,
                                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date()));
                        } catch (RuntimeException ex) {
                            result = getString(R.string.ir_send_failed, ex.getMessage());
                        }
                        String summary = result;
                        if (isAdded()) requireActivity().runOnUiThread(() -> p.setSummary(summary));
                    });
                    return true;
                });
                cat.addPreference(row);
            }
        }

        /** STEP_90 → "Step 90". */
        private static String roleLabel(TurntableProfile.Role role) {
            String s = role.name().replace('_', ' ').toLowerCase(Locale.ROOT);
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }
}
