package org.snurkle.badgeturntable.jobs;

import android.util.Log;

import org.json.JSONObject;
import org.snurkle.badgeturntable.motion.MoveFailedException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs one job at a time on its own thread. Starting a job while one runs fails with
 * busy. Aborting interrupts the body, which stops at its next wait; its finally blocks
 * stop the table and any recording.
 */
public final class JobRunner {
    private static final String TAG = "JobRunner";

    public interface Body {
        /** @return the job's result object (may be null) */
        JSONObject run(Job job) throws Exception;
    }

    public interface Listener {
        /** Started, progressed, prompted, answered or finished. Called on any thread. */
        void onJobChanged(Job job);
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /**
     * Job IDs are kept with the shots in shots.json across restarts, so each launch gets its
     * own prefix ("j-sqz4h1-3") rather than starting again at j-1.
     */
    private final String idPrefix = "j-" + Long.toString(System.currentTimeMillis() / 1000, 36) + "-";
    private Job current, last;
    private int jobN, promptN;

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Job start(Job.Kind kind, JSONObject params, Body body) throws ApiException {
        Job job;
        synchronized (this) {
            if (current != null) throw ApiException.busy(current);
            job = new Job(this, idPrefix + (++jobN), kind, params);
            current = job;
        }
        changed(job);
        worker.execute(() -> run(job, body));
        return job;
    }

    private void run(Job job, Body body) {
        //noinspection ResultOfMethodCallIgnored
        Thread.interrupted();  // a stray interrupt meant for an earlier job
        synchronized (job) {
            job.thread = Thread.currentThread();
        }
        JSONObject result = null;
        Job.State end;
        String code = null, message = null;
        try {
            if (job.abortRequested) throw new InterruptedException();
            result = body.run(job);
            end = Job.State.DONE;
        } catch (InterruptedException e) {
            end = Job.State.ABORTED;
        } catch (ApiException e) {
            end = job.abortRequested ? Job.State.ABORTED : Job.State.FAILED;
            code = e.code;
            message = e.getMessage();
        } catch (MoveFailedException e) {
            end = job.abortRequested ? Job.State.ABORTED : Job.State.FAILED;
            code = e.reason == MoveFailedException.Reason.RING_LOST ? "ring_lost" : "move_failed";
            message = e.userMessage();
        } catch (Exception e) {
            end = job.abortRequested ? Job.State.ABORTED : Job.State.FAILED;
            code = "internal";
            message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (end == Job.State.FAILED) Log.e(TAG, job.id + " failed", e);
        } finally {
            synchronized (job) {
                job.thread = null;
            }
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted();
        }
        job.finish(end, result, code, message);
        synchronized (this) {
            current = null;
            last = job;
        }
        Log.i(TAG, job.id + " " + job.kind + " " + end + (message != null ? ": " + message : ""));
        changed(job);
    }

    /** The running job, or null. */
    public synchronized Job current() {
        return current;
    }

    /** The running job, else the last one to finish, else null. */
    public synchronized Job currentOrLast() {
        return current != null ? current : last;
    }

    /** Answers the running job's prompt. The first answer wins; later ones get prompt_gone. */
    public Job answer(String promptId, String answer) throws ApiException {
        Job job = current();
        if (job == null) throw new ApiException(409, "no_job", "Nothing is running.");
        Job.Prompt p = job.prompt();
        if (p == null || !p.id.equals(promptId)) {
            throw new ApiException(409, "prompt_gone", "That question was already answered.");
        }
        if (!p.answers.contains(answer)) {
            throw ApiException.badRequest("answer", "The answer must be one of " + String.join(", ", p.answers) + ".");
        }
        if (!job.answer(promptId, answer)) {
            throw new ApiException(409, "prompt_gone", "That question was already answered.");
        }
        changed(job);
        return job;
    }

    /** Asks the running job to stop. It may still be winding down when this returns. */
    public Job abort() throws ApiException {
        Job job = current();
        if (job == null) throw new ApiException(409, "no_job", "Nothing is running.");
        job.abortRequested = true;
        synchronized (job) {
            if (job.thread != null) job.thread.interrupt();
        }
        job.say("Stopping…");
        return job;
    }

    synchronized String nextPromptId() {
        return "p-" + (++promptN);
    }

    void changed(Job job) {
        for (Listener l : listeners) {
            try {
                l.onJobChanged(job);
            } catch (RuntimeException e) {
                Log.w(TAG, "job listener failed", e);
            }
        }
    }

    public void shutdown() {
        Job job = current();
        if (job != null) {
            try {
                abort();
            } catch (ApiException ignored) {
            }
        }
        worker.shutdown();
    }
}
