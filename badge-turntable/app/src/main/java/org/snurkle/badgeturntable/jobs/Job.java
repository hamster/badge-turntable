package org.snurkle.badgeturntable.jobs;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Calibration, a capture, an extra shot or a move: one thing using the camera or the
 * turntable (Remote-API.md, Jobs). Started from the phone or the editor, shown on both.
 * The body runs on {@link JobRunner}'s thread and reports through step(), say() and ask().
 */
public final class Job {
    public enum Kind { MOVE, CALIBRATE, CAPTURE, EXTRA }

    public enum State { RUNNING, WAITING, DONE, FAILED, ABORTED }

    /** Who started a job, or stopped it. */
    public static final String PHONE = "phone", EDITOR = "editor";

    /** A question for the user. The first answer, from the phone or any editor tab, wins. */
    public static final class Prompt {
        public final String id;
        public final String kind;
        public final String text;
        public final List<String> answers;
        final JSONObject extra;
        final CompletableFuture<String> answer = new CompletableFuture<>();

        Prompt(String id, String kind, String text, List<String> answers, JSONObject extra) {
            this.id = id;
            this.kind = kind;
            this.text = text;
            this.answers = Collections.unmodifiableList(new ArrayList<>(answers));
            this.extra = extra == null ? new JSONObject() : extra;
        }

        /** A string field of the prompt's details ("view", "stage"…), or null. */
        public String detail(String key) {
            return extra.isNull(key) ? null : extra.optString(key, null);
        }

        public JSONObject toJson() {
            JSONObject o = Json.copy(extra);
            Json.put(o, "id", id);
            Json.put(o, "kind", kind);
            Json.put(o, "text", text);
            Json.put(o, "answers", Json.arr(answers));
            return o;
        }
    }

    public final String id;
    public final Kind kind;
    /** {@link #PHONE} or {@link #EDITOR}. */
    public final String source;
    /** Where its files go: {kind: shots | session | none, sessionId, name}. */
    public final JSONObject target;
    public final JSONObject params;
    public final long startedAtMs = System.currentTimeMillis();
    private final JobRunner runner;

    // Guarded by this.
    private State state = State.RUNNING;
    private Integer step, of;
    private String text = "Starting…";
    private Prompt prompt;
    private long endedAtMs;
    private JSONObject result;
    private String errorCode, errorMessage;
    private final List<String> shotIds = new ArrayList<>();
    private final List<String> filenames = new ArrayList<>();
    private String stoppedBy;
    private JSONObject camera;
    private String doneText;

    volatile boolean abortRequested;
    /** The thread running the body; guarded by this so an abort can't interrupt the next job. */
    Thread thread;

    Job(JobRunner runner, String id, Kind kind, String source, JSONObject target, JSONObject params) {
        this.runner = runner;
        this.id = id;
        this.kind = kind;
        this.source = source == null ? PHONE : source;
        this.target = target == null ? noTarget() : target;
        this.params = params == null ? new JSONObject() : params;
    }

    /** The target of a job that makes no files. */
    public static JSONObject noTarget() {
        return Json.obj("kind", "none", "sessionId", null, "name", null);
    }

    /** The session ID this job writes into, or null. */
    public String targetSessionId() {
        return "session".equals(target.optString("kind")) && !target.isNull("sessionId")
                ? target.optString("sessionId") : null;
    }

    /**
     * "The phone is capturing Mk9", "The editor is calibrating", for busy messages.
     */
    public String describe() {
        String who = EDITOR.equals(source) ? "The editor" : "The phone";
        String what;
        switch (kind) {
            case CALIBRATE: what = "is calibrating"; break;
            case CAPTURE: what = "is capturing"; break;
            case EXTRA: what = "is taking an extra shot"; break;
            default: what = "is moving the table"; break;
        }
        String kindName = target.optString("kind");
        String into = "session".equals(kindName) ? " " + target.optString("name")
                : "shots".equals(kindName) ? " (shots for the editor)" : "";
        return who + " " + what + into;
    }

    /** "Calibration", "Capture", … for messages. */
    public String kindLabel() {
        switch (kind) {
            case CALIBRATE: return "Calibration";
            case CAPTURE: return "A capture";
            case EXTRA: return "An extra shot";
            default: return "A turntable move";
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean finished() {
        return state == State.DONE || state == State.FAILED || state == State.ABORTED;
    }

    public synchronized String text() {
        return text;
    }

    public synchronized Prompt prompt() {
        return prompt;
    }

    public synchronized String errorMessage() {
        return errorMessage;
    }

    public synchronized List<String> shotIds() {
        return new ArrayList<>(shotIds);
    }

    public synchronized String stoppedBy() {
        return stoppedBy;
    }

    /** Who stopped it (the first one, if both did). */
    synchronized void setStoppedBy(String by) {
        if (stoppedBy == null) stoppedBy = by;
    }

    /** The camera settings in use, recorded in session.json. */
    public synchronized void setCamera(JSONObject settings) {
        camera = settings;
    }

    /** The progress text once the job is done (instead of "Done"). */
    public synchronized void setDoneText(String t) {
        doneText = t;
    }

    // ---- For the body ----------------------------------------------------------

    public void step(int n, int ofTotal, String newText) {
        synchronized (this) {
            step = n;
            of = ofTotal;
            text = newText;
        }
        runner.changed(this);
    }

    public void say(String newText) {
        synchronized (this) {
            text = newText;
        }
        runner.changed(this);
    }

    public void addShot(String shotId) {
        synchronized (this) {
            shotIds.add(shotId);
        }
        runner.changed(this);
    }

    /** A file written into the job's session. */
    public void addFile(String filename) {
        synchronized (this) {
            if (!filenames.contains(filename)) filenames.add(filename);
        }
        runner.changed(this);
    }

    /** Raises a prompt and waits for the first answer, from the phone or the editor. */
    public String ask(String promptKind, String promptText, List<String> answers, JSONObject extra)
            throws InterruptedException {
        Prompt p = new Prompt(runner.nextPromptId(), promptKind, promptText, answers, extra);
        synchronized (this) {
            prompt = p;
            state = State.WAITING;
        }
        runner.changed(this);
        try {
            return p.answer.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        } finally {
            boolean changed = false;
            synchronized (this) {
                if (prompt == p) {
                    prompt = null;
                    if (state == State.WAITING) state = State.RUNNING;
                    changed = true;
                }
            }
            if (changed) runner.changed(this);
        }
    }

    // ---- For the runner --------------------------------------------------------

    /** Applies an answer; false if the prompt has gone or doesn't match. */
    synchronized boolean answer(String promptId, String answer) {
        if (prompt == null || !prompt.id.equals(promptId)) return false;
        Prompt p = prompt;
        if (!p.answer.complete(answer)) return false;
        prompt = null;
        state = State.RUNNING;
        return true;
    }

    synchronized void finish(State end, JSONObject res, String code, String message) {
        state = end;
        result = res;
        errorCode = code;
        errorMessage = message;
        prompt = null;
        endedAtMs = System.currentTimeMillis();
        text = end == State.DONE ? (doneText != null ? doneText : "Done") : end == State.FAILED ? "Failed" : "Stopped";
    }

    public synchronized JSONObject toJson() {
        return Json.obj(
                "id", id,
                "kind", kind.name().toLowerCase(Locale.ROOT),
                "source", source,
                "target", target,
                "params", params,
                "state", state.name().toLowerCase(Locale.ROOT),
                "progress", Json.obj("step", step, "of", of, "text", text),
                "prompt", prompt == null ? null : prompt.toJson(),
                "startedAt", Json.time(startedAtMs),
                "endedAt", Json.time(endedAtMs),
                "result", result,
                "error", errorCode == null ? null : Json.obj("error", errorCode, "message", errorMessage),
                "stoppedBy", stoppedBy,
                "shotIds", Json.arr(shotIds),
                "filenames", Json.arr(filenames));
    }

    /** The job as session.json records it. */
    public synchronized JSONObject recordJson() {
        return Json.obj(
                "id", id,
                "kind", kind.name().toLowerCase(Locale.ROOT),
                "params", params,
                "startedAt", Json.time(startedAtMs),
                "endedAt", Json.time(endedAtMs),
                "state", state.name().toLowerCase(Locale.ROOT),
                "camera", camera,
                "shotIds", Json.arr(shotIds));
    }
}
