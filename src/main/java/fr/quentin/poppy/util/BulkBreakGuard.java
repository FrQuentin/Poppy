package fr.quentin.poppy.util;

/**
 * Shared reentrancy guard for {@code VeinMinerListener}/{@code TreeCapitatorListener}'s
 * synthetic per-block {@link org.bukkit.event.block.BlockBreakEvent} firing.
 *
 * <p>Firing a synthetic event for each chain-broken block is what lets
 * every protection plugin (WorldGuard, GriefPrevention, Poppy's own
 * {@code DeathChestManager}) and every logging plugin (CoreProtect) see
 * and, if needed, cancel each individual block — without it, only the
 * origin block the player actually clicked ever went through a real
 * event; the rest of the vein/tree was destroyed via
 * {@code Block#breakNaturally}, which never fires a
 * {@code BlockBreakEvent} at all, making it possible to grief straight
 * through a claim boundary by clicking one block just outside it.
 *
 * <p>The guard exists because that synthetic event is delivered to
 * every registered {@code BlockBreakEvent} listener — including
 * {@code VeinMinerListener} and {@code TreeCapitatorListener}
 * themselves. Without suppressing their own reaction to their own (or
 * each other's) synthetic events, a chain-broken ore/log block would be
 * treated as a brand new player-triggered break and start a nested flood
 * fill from inside the original loop. One shared static flag — not one
 * per listener — is what stops either listener's synthetic event from
 * re-triggering the other's chain logic too.
 */
public final class BulkBreakGuard {

    private static boolean active;

    private BulkBreakGuard() {
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Runs {@code action} with the guard held — re-entrant safe (a
     * nested {@code run} call is a no-op on the flag itself, it just
     * restores the previous state correctly on exit).
     */
    public static void run(Runnable action) {
        boolean previous = active;
        active = true;
        try {
            action.run();
        } finally {
            active = previous;
        }
    }
}