package fr.quentin.poppy.commands.afk;

import fr.quentin.poppy.manager.afk.AfkManager;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import fr.quentin.poppy.util.cooldown.CooldownManager;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

public class AfkCommand extends SafeCommand {

    private final AfkManager afkManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final CooldownStore cooldown;

    public AfkCommand(JavaPlugin plugin, AfkManager afkManager, Messages messages, PoppyConfig config,
                      PoppyLogger logger, CooldownManager cooldownManager) {
        super(plugin, messages);
        this.afkManager = afkManager;
        this.config = config;
        this.logger = logger;
        this.cooldown = cooldownManager.get("afk-toggle");
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("afk.too-fast", "time", DurationFormat.format(remaining)));
            return true;
        }

        cooldown.start(player.getUniqueId(), config.afkToggleCooldownMillis());

        boolean nowAfk = afkManager.toggle(player.getUniqueId());
        String messagePath = nowAfk ? "afk.now-afk" : "afk.no-longer-afk";

        logger.log(PoppyLogger.Category.AFK, player, nowAfk ? "went AFK (manual)" : "returned from AFK (manual)");

        Component broadcast = messages.get(messagePath, "player", player.getName());
        Bukkit.getServer().sendMessage(broadcast);
        return true;
    }
}