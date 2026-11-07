package org.snurkle.badgeturntable.ui;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import org.snurkle.badgeturntable.App;
import org.snurkle.badgeturntable.R;
import org.snurkle.badgeturntable.remote.RemoteServer;
import org.snurkle.badgeturntable.rig.Station;

import java.util.List;

/**
 * The slim remote-control line every screen shows while the server runs: name, address,
 * pairing code and which PCs are connected (Spec.md, Screens). Call start() and stop() from
 * the screen's onStart and onStop.
 */
final class RemoteBanner implements Station.Listener {
    private final Activity activity;
    private final TextView view;
    private final Station station;

    RemoteBanner(Activity activity, TextView view) {
        this.activity = activity;
        this.view = view;
        this.station = App.station(activity);
    }

    void start() {
        station.addListener(this);
        show();
    }

    void stop() {
        station.removeListener(this);
    }

    @Override
    public void onStationChanged() {
        activity.runOnUiThread(this::show);
    }

    private void show() {
        RemoteServer r = station.server();
        if (r == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        if (!r.running()) {
            view.setText(r.error());
            return;
        }
        List<String> addresses = RemoteServer.addresses();
        String code = r.code();
        StringBuilder text = new StringBuilder(activity.getString(R.string.remote_banner, r.name(),
                addresses.isEmpty() ? activity.getString(R.string.remote_no_wifi) : addresses.get(0) + ":" + r.port(),
                code.substring(0, 3) + " " + code.substring(3)));
        List<String> clients = r.clientNames();
        if (!clients.isEmpty()) {
            text.append(" · ").append(activity.getString(R.string.remote_controlled_from, String.join(", ", clients)));
        }
        view.setText(text);
    }
}
