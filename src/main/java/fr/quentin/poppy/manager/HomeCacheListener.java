package fr.quentin.poppy.manager;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class HomeCacheListener implements Listener {

    private final HomeManager homeManager;

    public HomeCacheListener(HomeManager homeManager) {
        this.homeManager = homeManager;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        homeManager.unload(event.getPlayer().getUniqueId());
    }
}