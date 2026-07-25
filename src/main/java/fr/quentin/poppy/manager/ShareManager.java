package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds short-lived tokens created by /sharehome, each mapped to the
 * sharer's UUID and home name — not a {@link fr.quentin.poppy.model.Home}
 * snapshot. Resolving the token re-fetches the home from
 * {@link HomeManager} at the moment /poppygoto is actually used (see
 * {@link fr.quentin.poppy.commands.PoppyGotoCommand}), the same
 * re-resolve-at-teleport-time approach {@code HomeCommand} uses via a
 * {@link java.util.function.Supplier}. Storing a live reference rather
 * than a frozen snapshot matters here: if the owner deletes or moves that
 * home after sharing it but before someone clicks the link, the link
 * should reflect that (fail with "not found", or teleport to the new
 * location) rather than silently sending people to stale coordinates.
 *
 * <p>A token is the only access control for the click-to-teleport link —
 * see {@link fr.quentin.poppy.commands.PoppyGotoCommand} — so it's scoped
 * to {@code sharehome-expiry-seconds} (config.yml, 300s by default,
 * minimum 30s) and self-removes via a scheduled task rather than
 * lingering in memory. That cleanup task is lost on a plugin
 * reload/server restart (a fresh {@link ShareManager} instance has no
 * memory of it), but so does the underlying map itself — there's nothing
 * to leak, the whole thing starts empty again.
 */
public class ShareManager {

    public record SharedHome(UUID ownerUuid, String homeName) {
    }

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final Map<String, SharedHome> shares = new HashMap<>();

    public ShareManager(JavaPlugin plugin, PoppyConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public String share(UUID ownerUuid, String homeName) {
        String token = UUID.randomUUID().toString().replace("-", "");
        shares.put(token, new SharedHome(ownerUuid, homeName));

        Bukkit.getScheduler().runTaskLater(plugin, () -> shares.remove(token), config.sharehomeExpirySeconds() * 20L);

        return token;
    }

    public SharedHome get(String token) {
        return shares.get(token);
    }
}