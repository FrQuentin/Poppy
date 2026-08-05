package fr.quentin.poppy.commands.misc;

import fr.quentin.poppy.manager.playtime.PlaytimeManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles /playtime: with no argument, shows the sender's own playtime
 * and first-join date. With a player name (requires
 * {@code poppy.playtime.admin}), shows that player's instead — resolved
 * via {@link PlaytimeManager#resolveByName}, which works even if they're
 * currently offline as long as they've joined this server before.
 */
public class PlaytimeCommand extends SafeCommand {

    private static final DateTimeFormatter JOIN_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMMM dd, yyyy (hh'H'mm a)", Locale.ENGLISH);

    private final PlaytimeManager playtimeManager;

    public PlaytimeCommand(JavaPlugin plugin, PlaytimeManager playtimeManager, Messages messages) {
        super(plugin, messages);
        this.playtimeManager = playtimeManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        if (args.length == 0) {
            Player player = requirePlayer(sender);
            if (player == null) {
                return true;
            }
            sendPlaytime(player, player.getUniqueId(), player.getFirstPlayed(), null);
            return true;
        }

        if (!sender.hasPermission("poppy.playtime.admin")) {
            sender.sendMessage(messages.get("general.no-permission"));
            return true;
        }

        UUID targetUuid = playtimeManager.resolveByName(args[0]);
        if (targetUuid == null) {
            sender.sendMessage(messages.get("playtime.admin-target-not-found", "player", args[0]));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
        String targetName = target.getName() != null ? target.getName() : args[0];

        sendPlaytime(sender, targetUuid, target.getFirstPlayed(), targetName);
        return true;
    }

    private void sendPlaytime(CommandSender sender, UUID uuid, long firstPlayedMillis, String targetName) {
        if (targetName != null) {
            sender.sendMessage(messages.get("playtime.admin-header", "player", targetName));
        }

        long totalMillis = playtimeManager.getTotalPlaytimeMillis(uuid);
        long totalMinutes = totalMillis / (1000L * 60);
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;

        sender.sendMessage(messages.get("playtime.line-playtime",
                "days", String.valueOf(days), "hours", String.valueOf(hours), "minutes", String.valueOf(minutes)));

        String joinDate = JOIN_DATE_FORMAT.format(
                Instant.ofEpochMilli(firstPlayedMillis).atZone(ZoneId.systemDefault()));
        sender.sendMessage(messages.get("playtime.line-join-date", "date", joinDate));
    }

    /**
     * @param targetName null for the sender's own playtime (no admin
     *                   header shown); the target's name otherwise.
     */
    private void sendPlaytime(Player sender, UUID uuid, long firstPlayedMillis, String targetName) {
        if (targetName != null) {
            sender.sendMessage(messages.get("playtime.admin-header", "player", targetName));
        }

        long totalMillis = playtimeManager.getTotalPlaytimeMillis(uuid);
        long totalMinutes = totalMillis / (1000L * 60);
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;

        sender.sendMessage(messages.get("playtime.line-playtime",
                "days", String.valueOf(days), "hours", String.valueOf(hours), "minutes", String.valueOf(minutes)));

        String joinDate = JOIN_DATE_FORMAT.format(
                Instant.ofEpochMilli(firstPlayedMillis).atZone(ZoneId.systemDefault()));
        sender.sendMessage(messages.get("playtime.line-join-date", "date", joinDate));
    }
}