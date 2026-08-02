package fr.quentin.poppy.util;

/**
 * Formats a duration in seconds as a compact human-readable string
 * ("45s", "1m20s", "2m", "1h5m", "3h") — used for cooldown messages
 * instead of showing raw seconds once a cooldown gets past a minute (or,
 * for longer waits like the /fly budget cooldown, past an hour).
 *
 * <p>Each unit is only shown if non-zero, and only down to the coarsest
 * two units that matter — seconds are dropped entirely once the duration
 * reaches an hour, since "1h5m12s" is more precision than a cooldown
 * message needs.
 */
public final class DurationFormat {

    private DurationFormat() {
    }

    public static String format(long totalSeconds) {
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }

        if (totalSeconds < 3600) {
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            return seconds == 0 ? minutes + "m" : minutes + "m" + seconds + "s";
        }

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        return minutes == 0 ? hours + "h" : hours + "h" + minutes + "m";
    }
}