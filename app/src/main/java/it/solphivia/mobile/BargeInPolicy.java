package it.solphivia.mobile;

/** Raw-energy barge-in: sustained voice-level RMS while the voice speaks stops it at once.
 *  One frame = 100 ms of 16 kHz mic audio. The trigger level adapts to the quietest
 *  recent frame (the real noise/echo floor), with an absolute minimum so a dead-quiet
 *  room cannot make tiny sounds trigger. With echo cancellation the trigger is fast
 *  (3 frames ≈ 300 ms); without it the bar is higher so the speaker's own TTS echo
 *  is unlikely to self-interrupt. */
final class BargeInPolicy {
    private static final int WINDOW = 60; // ~6 s of 100 ms frames
    private final int framesNeeded;
    private final double ratio;
    private final int absoluteFloor;
    private final double[] history = new double[WINDOW];
    private int filled, cursor, run;
    BargeInPolicy(boolean echoCancellation) {
        framesNeeded = echoCancellation ? 3 : 5;
        ratio = echoCancellation ? 3.0 : 3.5;
        absoluteFloor = echoCancellation ? 900 : 1100;
    }
    void reset() { run = 0; }
    private double floorLevel() {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < filled; i++) if (history[i] < min) min = history[i];
        return min;
    }
    boolean frame(double rms) {
        double floor = floorLevel();
        history[cursor] = rms;
        cursor = (cursor + 1) % WINDOW;
        if (filled < WINDOW) filled++;
        double threshold = floor == Double.MAX_VALUE ? absoluteFloor : Math.max(absoluteFloor, floor * ratio);
        if (rms >= threshold) run++;
        else if (run > 0) run--; // a short quiet gap does not forget the ongoing speech
        return run >= framesNeeded;
    }
}
