package org.snurkle.badgeturntable.motion;

public final class MoveFailedException extends Exception {
    public enum Reason { NO_MOVEMENT, RING_LOST, NO_REMOTE, TIMEOUT, STEP_SIZE }

    public final Reason reason;

    public MoveFailedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String userMessage() {
        switch (reason) {
            case NO_MOVEMENT: return "Can't move the turntable. Check that IR reaches the table and that it's switched on.";
            case RING_LOST: return "Can't see the encoder ring. Is something blocking it?";
            case STEP_SIZE: return getMessage();
            case NO_REMOTE: return "This turntable profile has no code for that move.";
            default: return "The turntable didn't reach its position in time.";
        }
    }
}
