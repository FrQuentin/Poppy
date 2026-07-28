package fr.quentin.poppy.util;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;

/**
 * Central registry of every player-facing {@link CooldownStore} in the
 * plugin, each tagged with a display name — backs {@code /cooldowns},
 * which lists them all for the requesting player. A command that wants
 * to show up there simply calls {@link #register} once, right after
 * creating its own {@link CooldownStore}, in its constructor.
 *
 * <p>{@link LinkedHashMap} preserves registration order, so the display
 * order in {@code /cooldowns} matches the order commands are wired in
 * {@code Poppy#onEnable} — Feed, Heal, RTP, ShareHome, in that order by
 * default.
 */
public final class CooldownRegistry {

    public record Entry(String displayName, CooldownStore store) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public void register(String displayName, CooldownStore store) {
        entries.put(displayName, new Entry(displayName, store));
    }

    public Collection<Entry> entries() {
        return entries.values();
    }
}