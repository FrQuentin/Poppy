package fr.quentin.poppy.util;

import fr.quentin.poppy.manager.tpa.TpaManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared tab-completion helper: suggests online player names matching a
 * prefix, excluding the player doing the completing and anyone they
 * can't currently see (vanished staff via {@link Player#canSee(Player)}).
 * Without that check, tab-completing /tpa (or any command using this)
 * would leak the presence of vanished moderators to any player — exactly
 * the kind of thing vanish exists to prevent, and something cheaters
 * routinely probe for. Used by /tpa and /tpahere, which target anyone
 * online (unlike /tpaccept and /tpadeny, which use
 * {@link TpaManager#pendingRequesterNames} to
 * only suggest players with an actual pending request).
 */
public final class PlayerNameSuggestions {

    private PlayerNameSuggestions() {
    }

    public static List<String> onlineExcept(Player exclude, String prefix) {
        String partial = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(exclude.getUniqueId())) {
                continue;
            }
            if (!exclude.canSee(online)) {
                continue;
            }
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                names.add(online.getName());
            }
        }
        return names;
    }
}