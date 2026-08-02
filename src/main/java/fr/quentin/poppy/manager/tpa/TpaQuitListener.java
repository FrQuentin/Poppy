package fr.quentin.poppy.manager.tpa;

import fr.quentin.poppy.util.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.NonNull;

/**
 * Cleans up a player's pending TPA requests on quit — see
 * {@link TpaManager#removePlayer}. Also reminds a player at login if
 * they currently have incoming requests blocked via {@code /tpatoggle} —
 * without this, a player who toggled it off, then forgot, has no way to
 * know their protection is (still) active until they try /tpatoggle
 * again or a request silently never reaches them.
 */
public class TpaQuitListener implements Listener {

    private final TpaManager tpaManager;
    private final Messages messages;

    public TpaQuitListener(TpaManager tpaManager, Messages messages) {
        this.tpaManager = tpaManager;
        this.messages = messages;
    }

    @EventHandler
    public void onJoin(@NonNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!tpaManager.isAcceptingRequests(player.getUniqueId())) {
            player.sendMessage(messages.get("tpa.toggle-off-reminder"));
        }
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        tpaManager.removePlayer(event.getPlayer().getUniqueId());
    }
}