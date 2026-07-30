package fr.quentin.poppy.util;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Formats a whole-number currency amount as {@code "$1,234,567"} —
 * shared by /money and /pay so both display balances identically.
 */
public final class MoneyFormat {

    private MoneyFormat() {
    }

    public static String format(long amount) {
        return "$" + NumberFormat.getIntegerInstance(Locale.US).format(amount);
    }
}