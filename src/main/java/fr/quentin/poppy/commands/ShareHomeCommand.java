package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Handles /sharehome: broadcasts a clickable teleport link for one of the
 * sender's homes to the whole server. Clicking the link runs
 * {@code /poppygoto <token>} — see {@link fr.quentin.poppy.commands.PoppyGotoCommand}.
 *
 * <p>Rate-limited via {@code sharehome-cooldown-seconds} in config.yml,
 * tracked in a shared {@link CooldownStore} — deliberately never cleared
 * on quit or per-player, same reasoning as {@link AfkCommand}: a cooldown
 * on a server-wide broadcast command must survive a disconnect/reconnect,
 * and the store purges expired entries on its own periodic sweep.
 *
 * <p>Still implements {@link Listener} (with no handlers) purely so
 * {@code Poppy#onEnable}'s existing {@code registerEvents(shareHomeCommand, this)}
 * call keeps compiling — it used to carry a {@code PlayerQuitEvent}
 * handler that cleared the old raw cooldown map, removed once the switch
 * to {@link CooldownStore} made it unnecessary.
 */
public class ShareHomeCommand extends SafeCommand implements TabCompleter {

    private final HomeManager homeManager;
    private final ShareManager shareManager;
    private final PoppyConfig config;
    private final PoppyStats stats;
    private final PoppyLogger logger;
    private final CooldownStore cooldown;

    public ShareHomeCommand(JavaPlugin plugin, HomeManager homeManager, ShareManager shareManager, Messages messages,
                            PoppyConfig config, PoppyStats stats, PoppyLogger logger, CooldownRegistry registry) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.shareManager = shareManager;
        this.config = config;
        this.stats = stats;
        this.logger = logger;
        this.cooldown = new CooldownStore(plugin);
        registry.register("Share Home", cooldown);
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