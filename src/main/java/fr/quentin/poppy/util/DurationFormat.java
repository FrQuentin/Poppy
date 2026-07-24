package fr.quentin.poppy.util;

/**
 * Formats a duration in seconds as a compact human-readable string
 * ("45s", "1m20s", "2m") — used for cooldown messages instead of showing
 * raw seconds once a cooldown gets past a minute or so.
 */
public final class DurationFormat {

    private DurationFormat() {
    }

    public static String format(long totalSeconds) {
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }

        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        return seconds == 0 ? minutes + "m" : minutes + "m" + seconds + "s";
    }
}