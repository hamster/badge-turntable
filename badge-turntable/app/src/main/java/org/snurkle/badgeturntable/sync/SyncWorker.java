package org.snurkle.badgeturntable.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.snurkle.badgeturntable.session.Session;
import org.snurkle.badgeturntable.session.SessionStore;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Uploads sessions marked for sync to the Samba share, mirroring the local
 * layout (_badges/ and _capture/ under the configured base path). A file is
 * re-uploaded when its size or modified time changed since the last upload.
 * Also refreshes the known badge IDs from the share.
 */
public final class SyncWorker extends Worker {
    private static final String TAG = "SyncWorker";
    private static final String WORK = "samba-sync";
    private static final String STATE_PREFS = "sync_state";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SmbSettings settings = SmbSettings.load(context);
        if (!settings.isConfigured()) return Result.success();  // nothing to do until it's set up
        if (!SmbClient.reachable(settings)) return Result.retry();

        SessionStore store = new SessionStore(context);
        SharedPreferences state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        boolean failed = false;
        try (SmbClient smb = new SmbClient(settings)) {
            smb.open();
            for (Session s : store.list()) {
                if (!s.syncToSamba || s.syncState == Session.SyncState.SYNCED) continue;
                try {
                    uploadTree(smb, settings, store.base(), store.badgeDir(s), state);
                    uploadTree(smb, settings, store.base(), store.captureDir(s), state);
                    s.syncState = Session.SyncState.SYNCED;
                } catch (Exception e) {
                    Log.w(TAG, "sync failed for " + s.effectiveSlug(), e);
                    s.syncState = Session.SyncState.FAILED;
                    failed = true;
                }
                store.save(s);
            }
            try {
                KnownBadgeIds.rememberFromShare(context, smb.scanBadgeIds());
            } catch (Exception e) {
                Log.w(TAG, "badge id scan failed", e);
            }
        } catch (Exception e) {
            Log.w(TAG, "sync failed", e);
            return Result.retry();
        }
        return failed ? Result.retry() : Result.success();
    }

    private static void uploadTree(SmbClient smb, SmbSettings settings, File base, File dir,
                                   SharedPreferences state) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                uploadTree(smb, settings, base, f, state);
                continue;
            }
            if (f.getName().endsWith(".tmp")) continue;
            String rel = base.toURI().relativize(f.toURI()).getPath();  // "_badges/defcon/x/front.jpg"
            String stamp = f.length() + ":" + f.lastModified();
            if (stamp.equals(state.getString(rel, null))) continue;
            smb.upload(f, settings.remote(rel));
            state.edit().putString(rel, stamp).apply();
        }
    }
}
