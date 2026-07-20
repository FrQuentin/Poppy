package fr.quentin.poppy.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared tab-completion helper: suggests online player names matching a
 * prefix, excluding the player doing the completing. Used by /tpa and
 * /tpahere, which target anyone online (unlike /tpaccept and /tpadeny,
 * which use {@link fr.quentin.poppy.manager.TpaManager#pendingRequesterNames}
 * to only suggest players with an actual pending request).
 */
public final class PlayerNameSuggestions {

    private PlayerNameSuggestions() {
    }

    public static List<String> onlineExcept(Player exclude, String prefix) {
        String partial = prefix.toLowerCase();
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(exclude.getUniqueId())) {
                continue;
            }
            if (online.getName().toLowerCase().startsWith(partial)) {
                names.add(online.getName());
            }
        }
        return names;
    }
}