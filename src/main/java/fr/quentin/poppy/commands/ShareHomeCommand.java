package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
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

public class ShareHomeCommand extends SafeCommand implements TabCompleter, Listener {

    private final HomeManager homeManager;
    private final ShareManager shareManager;
    private final PoppyConfig config;
    private final PoppyStats stats;
    private final PoppyLogger logger;

    private final Map<UUID, Long> lastUse = new HashMap<>();

    public ShareHomeCommand(JavaPlugin plugin, HomeManager homeManager, ShareManager shareManager, Messages messages,
                            PoppyConfig config, PoppyStats stats, PoppyLogger logger) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.shareManager = shareManager;
        this.config = config;
        this.stats = stats;
        this.logger = logger;
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

        logger.log(PoppyLogger.Category.SHARE, player, "shared home '" + home.name() + "'");

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
        long cooldownMillis = config.sharehomeCooldownMillis();
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