package com.gameplatform.plugin.task;

/**
 * Maps a child operation's 0-100 progress into a slice of the parent's
 * 0-100 progress.
 */
public record ProgressWindow(int startPercent, int endPercent) {

    public ProgressWindow {
        if (startPercent < 0 || startPercent > 100
                || endPercent < 0 || endPercent > 100
                || startPercent > endPercent) {
            throw new IllegalArgumentException(
                    "progress window 必须满足 0 <= start <= end <= 100");
        }
    }

    public static ProgressWindow full() {
        return new ProgressWindow(0, 100);
    }

    public int map(int childPercent) {
        int clamped = Math.max(0, Math.min(100, childPercent));
        return startPercent + (endPercent - startPercent) * clamped / 100;
    }
}
