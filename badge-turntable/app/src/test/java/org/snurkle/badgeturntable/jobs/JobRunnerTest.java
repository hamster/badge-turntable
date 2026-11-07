package org.snurkle.badgeturntable.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.snurkle.badgeturntable.motion.MoveFailedException;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class JobRunnerTest {
    private final JobRunner runner = new JobRunner();

    @After
    public void tearDown() {
        runner.shutdown();
    }

    private static void await(Job job, Job.State state) throws InterruptedException {
        for (int i = 0; i < 200 && job.state() != state; i++) Thread.sleep(10);
        assertEquals(state, job.state());
    }

    private static Job.Prompt awaitPrompt(Job job) throws InterruptedException {
        for (int i = 0; i < 200 && job.prompt() == null; i++) Thread.sleep(10);
        return job.prompt();
    }

    @Test
    public void oneAtATime() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Job first = runner.start(Job.Kind.CALIBRATE, null, job -> {
            release.await();
            return Json.obj("ok", true);
        });
        try {
            runner.start(Job.Kind.CAPTURE, null, job -> null);
            fail();
        } catch (ApiException e) {
            assertEquals("busy", e.code);
            assertEquals(409, e.status);
            assertEquals(first.id, e.body().getString("jobId"));
        }
        release.countDown();
        await(first, Job.State.DONE);
        assertNull(runner.current());
        assertEquals(first, runner.currentOrLast());
        assertTrue(first.toJson().getJSONObject("result").getBoolean("ok"));
        runner.start(Job.Kind.CAPTURE, null, job -> null);  // free again
    }

    @Test
    public void firstAnswerWins() throws Exception {
        String[] got = {null};
        Job job = runner.start(Job.Kind.CAPTURE, Json.obj("auto", true), j -> {
            got[0] = j.ask("move_failed", "The table didn't move.", Arrays.asList("retry", "manual", "stop"),
                    Json.obj("view", "side-090", "targetDeg", 90));
            return null;
        });
        Job.Prompt p = awaitPrompt(job);
        assertEquals(Job.State.WAITING, job.state());
        JSONObject pj = job.toJson().getJSONObject("prompt");
        assertEquals("side-090", pj.getString("view"));
        assertEquals("move_failed", pj.getString("kind"));
        assertEquals(3, pj.getJSONArray("answers").length());

        try {
            runner.answer(p.id, "maybe");
            fail();
        } catch (ApiException e) {
            assertEquals("bad_request", e.code);
        }
        runner.answer(p.id, "manual");
        try {
            runner.answer(p.id, "stop");  // from another tab, too late
            fail();
        } catch (ApiException e) {
            assertEquals("prompt_gone", e.code);
        }
        await(job, Job.State.DONE);
        assertEquals("manual", got[0]);
    }

    @Test
    public void abortInterruptsAWait() throws Exception {
        Job job = runner.start(Job.Kind.MOVE, null, j -> {
            Thread.sleep(10_000);
            return null;
        });
        Thread.sleep(50);
        runner.abort();
        await(job, Job.State.ABORTED);
        assertEquals("Stopped", job.text());
        try {
            runner.abort();
            fail();
        } catch (ApiException e) {
            assertEquals("no_job", e.code);
        }
    }

    @Test
    public void abortWhileWaitingForAnAnswer() throws Exception {
        Job job = runner.start(Job.Kind.CAPTURE, null, j -> {
            j.ask("position", "Turn the table to 90°", Arrays.asList("ready", "stop"), null);
            return null;
        });
        Job.Prompt p = awaitPrompt(job);
        runner.abort();
        await(job, Job.State.ABORTED);
        assertNull(job.prompt());
        try {
            runner.answer(p.id, "ready");
            fail();
        } catch (ApiException e) {
            assertEquals("no_job", e.code);
        }
    }

    @Test
    public void failuresKeepTheirCodes() throws Exception {
        Job moved = runner.start(Job.Kind.MOVE, null, j -> {
            throw new MoveFailedException(MoveFailedException.Reason.RING_LOST, "gone");
        });
        await(moved, Job.State.FAILED);
        JSONObject err = moved.toJson().getJSONObject("error");
        assertEquals("ring_lost", err.getString("error"));
        assertEquals(new MoveFailedException(MoveFailedException.Reason.RING_LOST, "").userMessage(), err.getString("message"));

        Job other = runner.start(Job.Kind.CALIBRATE, null, j -> {
            throw new IllegalStateException("boom");
        });
        await(other, Job.State.FAILED);
        assertEquals("internal", other.toJson().getJSONObject("error").getString("error"));
        assertEquals("boom", other.errorMessage());
    }

    @Test
    public void progressAndShotsShowInTheJson() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Job job = runner.start(Job.Kind.CAPTURE, null, j -> {
            j.step(2, 6, "Moving to 90°…");
            j.addShot("s-1");
            release.await(5, TimeUnit.SECONDS);
            j.setDoneText("Done. The shots are ready for the editor.");
            return null;
        });
        for (int i = 0; i < 200 && job.shotIds().isEmpty(); i++) Thread.sleep(10);
        JSONObject j = job.toJson();
        assertEquals(2, j.getJSONObject("progress").getInt("step"));
        assertEquals(6, j.getJSONObject("progress").getInt("of"));
        assertEquals("capture", j.getString("kind"));
        assertEquals("running", j.getString("state"));
        assertEquals("s-1", j.getJSONArray("shotIds").getString(0));
        release.countDown();
        await(job, Job.State.DONE);
        assertEquals("Done. The shots are ready for the editor.", job.text());
        assertTrue(job.toJson().getString("endedAt").endsWith("Z"));
    }
}
