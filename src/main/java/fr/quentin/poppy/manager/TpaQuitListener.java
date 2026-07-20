package fr.quentin.poppy.manager;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.NonNull;

/**
 * Evicts every pending /tpa or /tpahere request involving a player once
 * they disconnect — see {@link TpaManager#removePlayer}.
 */
public class TpaQuitListener implements Listener {

    private final TpaManager tpaManager;

    public TpaQuitListener(TpaManager tpaManager) {
        this.tpaManager = tpaManager;
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        tpaManager.removePlayer(event.getPlayer().getUniqueId());
    }
}