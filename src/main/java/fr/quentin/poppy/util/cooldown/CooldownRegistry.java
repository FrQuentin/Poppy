package fr.quentin.poppy.util.cooldown;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Central registry of every player-facing cooldown-like state in the
 * plugin, each tagged with a display name — backs {@code /cooldowns}.
 *
 * <p>Each entry is a remaining-seconds provider ({@code Function<UUID, Long>})
 * rather than a raw {@link CooldownStore} — {@link #register} still
 * accepts a {@link CooldownStore} directly for the common case, but
 * {@link #registerCustom} exists for anything whose remaining time isn't
 * a single {@link CooldownStore} alone. Fly is the reason this exists:
 * {@code FlyManager#isLocked}/{@code displayRemainingSeconds} (what
 * {@code /fly} and {@code /flytime} actually show) combine its own
 * post-damage/max-duration lockout with an active combat tag — a plain
 * {@code CooldownStore} registration only ever saw the lockout half, so a
 * player blocked purely by combat tag saw "/fly: try again in Xs" and
 * "/cooldowns: Fly — Ready" at the same time. Registering with
 * {@code FlyManager::displayRemainingSeconds} instead makes all three
 * displays agree by construction.
 */
public final class CooldownRegistry {

    public record Entry(String displayName, Function<UUID, Long> remainingSecondsProvider) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public void register(String displayName, CooldownStore store) {
        entries.put(displayName, new Entry(displayName, store::remainingSeconds));
    }

    public void registerCustom(String displayName, Function<UUID, Long> remainingSecondsProvider) {
        entries.put(displayName, new Entry(displayName, remainingSecondsProvider));
    }

    public Collection<Entry> entries() {
        return entries.values();
    }
}