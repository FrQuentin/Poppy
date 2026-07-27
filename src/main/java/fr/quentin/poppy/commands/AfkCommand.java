package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.AfkManager;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles /afk: toggles the sender's AFK status and broadcasts the change
 * to the whole server. The actual AFK state is tracked by {@link AfkManager};
 * this class is only responsible for the command entry point and the message.
 *
 * <p>Rate-limited via {@code afk-toggle-cooldown-seconds} in config.yml —
 * without this, spamming /afk (trivially macro-able) broadcasts a message
 * to every online player, writes a log line, and nudges
 * {@code TabHealthListener}'s cache key on every single call: a free,
 * permission-less way to flood the chat of every connected player.
 *
 * <p>Deliberately not cleared on quit — same reasoning as
 * {@code FeedCommand}/{@code HealCommand}: clearing it would let a player
 * dodge the cooldown by disconnecting and reconnecting, which a
 * macro-driven griefer would happily automate too.
 *
 * @see fr.quentin.poppy.manager.AfkListener AfkListener, which clears AFK automatically on movement
 * @see fr.quentin.poppy.manager.AutoAfkTask AutoAfkTask, which sets AFK automatically after inactivity
 */
public class AfkCommand extends SafeCommand {

    private final AfkManager afkManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;

    private final Map<UUID, Long> lastToggle = new HashMap<>();

    public AfkCommand(JavaPlugin plugin, AfkManager afkManager, Messages messages, PoppyConfig config, PoppyLogger logger) {
        super(plugin, messages);
        this.afkManager = afkManager;
        this.config = config;
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
            player.sendMessage(messages.get("afk.too-fast", "time", DurationFormat.format(remaining)));
            return true;
        }

        lastToggle.put(player.getUniqueId(), System.currentTimeMillis());

        boolean nowAfk = afkManager.toggle(player.getUniqueId());
        String messagePath = nowAfk ? "afk.now-afk" : "afk.no-longer-afk";

        logger.log(PoppyLogger.Category.AFK, player, nowAfk ? "went AFK (manual)" : "returned from AFK (manual)");

        Component broadcast = messages.get(messagePath, "player", player.getName());
        Bukkit.getServer().sendMessage(broadcast);
        return true;
    }

    private long cooldownRemaining(UUID uuid) {
        long cooldownMillis = config.afkToggleCooldownMillis();
        if (cooldownMillis <= 0) {
            return 0;
        }
        Long last = lastToggle.get(uuid);
        if (last == null) {
            return 0;
        }
        long remainingMillis = cooldownMillis - (System.currentTimeMillis() - last);
        return remainingMillis <= 0 ? 0 : (remainingMillis / 1000) + 1;
    }
}