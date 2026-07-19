package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyStats;
import fr.quentin.poppy.util.SafeCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles /sharehome: broadcasts a clickable teleport link for one of the
 * sender's homes to the whole server. Clicking the link runs
 * {@code /poppygoto <token>}, where the short-lived {@link ShareManager}
 * token is the actual access control — see {@link PoppyGotoCommand}.
 *
 * <p>Rate-limited via {@code sharehome-cooldown-seconds} in config.yml
 * (same pattern as {@link RtpCommand}) to stop a player from flooding the
 * whole server's chat with repeated shares.
 */
public class ShareHomeCommand extends SafeCommand implements TabCompleter, Listener {

    private final HomeManager homeManager;
    private final ShareManager shareManager;
    private final PoppyStats stats;
    private final long cooldownMillis;

    private final Map<UUID, Long> lastUse = new HashMap<>();

    public ShareHomeCommand(JavaPlugin plugin, HomeManager homeManager, ShareManager shareManager, Messages messages, PoppyStats stats) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.shareManager = shareManager;
        this.stats = stats;
        this.cooldownMillis = Math.max(0, plugin.getConfig().getInt("sharehome-cooldown-seconds", 30)) * 1000L;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("sharehome.cooldown", "seconds", String.valueOf(remaining)));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(messages.get("sharehome.usage"));
            return true;
        }

        String name = args[0];
        Home home = homeManager.getHome(player.getUniqueId(), name);
        if (home == null) {
            player.sendMessage(messages.get("sharehome.not-found"));
            return true;
        }

        String token = shareManager.share(home);
        lastUse.put(player.getUniqueId(), System.currentTimeMillis());
        stats.incrementSharesCreated();

        Component prefix = messages.get("sharehome.broadcast-prefix", "player", player.getName(), "home", home.name());
        Component clickText = messages.get("sharehome.click-text", "home", home.name())
                .clickEvent(ClickEvent.runCommand("/poppygoto " + token))
                .hoverEvent(HoverEvent.showText(messages.get("sharehome.click-hover", "home", home.name())));

        Bukkit.getServer().sendMessage(prefix.append(clickText));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        return homeManager.suggestHomeNames(player.getUniqueId(), args[0]);
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    private long cooldownRemaining(UUID uuid) {
        if (cooldownMillis <= 0) {
            return 0;
        }
        Long last = lastUse.get(uuid);
        if (last == null) {
            return 0;
        }
        long remainingMillis = cooldownMillis - (System.currentTimeMillis() - last);
        return remainingMillis <= 0 ? 0 : (remainingMillis / 1000) + 1;
    }
}