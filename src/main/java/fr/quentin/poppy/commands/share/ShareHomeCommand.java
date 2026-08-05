package fr.quentin.poppy.commands.share;

import fr.quentin.poppy.manager.home.HomeManager;
import fr.quentin.poppy.manager.share.ShareManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.PoppyStats;
import fr.quentin.poppy.util.SafeCommand;
import fr.quentin.poppy.util.cooldown.CooldownManager;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;

public class ShareHomeCommand extends SafeCommand implements TabCompleter {

    private final HomeManager homeManager;
    private final ShareManager shareManager;
    private final PoppyConfig config;
    private final PoppyStats stats;
    private final PoppyLogger logger;
    private final CooldownStore cooldown;

    public ShareHomeCommand(JavaPlugin plugin, HomeManager homeManager, ShareManager shareManager, Messages messages,
                            PoppyConfig config, PoppyStats stats, PoppyLogger logger, CooldownManager cooldownManager) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.shareManager = shareManager;
        this.config = config;
        this.stats = stats;
        this.logger = logger;
        this.cooldown = cooldownManager.get("sharehome", "Share Home");
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("sharehome.cooldown", "time", DurationFormat.format(remaining)));
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

        String token = shareManager.share(player.getUniqueId(), home.name());
        cooldown.start(player.getUniqueId(), config.sharehomeCooldownMillis());
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
}