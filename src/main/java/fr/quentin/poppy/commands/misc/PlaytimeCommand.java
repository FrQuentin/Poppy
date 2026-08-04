package fr.quentin.poppy.commands.misc;

import fr.quentin.poppy.manager.playtime.PlaytimeManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Handles /playtime: shows the sender's total accumulated playtime (see
 * {@link PlaytimeManager}) and their first-join date (via Bukkit's own
 * {@link Player#getFirstPlayed()}, which the server already tracks
 * reliably on its own).
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
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long totalMillis = playtimeManager.getTotalPlaytimeMillis(player.getUniqueId());
        long totalMinutes = totalMillis / (1000L * 60);
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;

        player.sendMessage(messages.get("playtime.line-playtime",
                "days", String.valueOf(days), "hours", String.valueOf(hours), "minutes", String.valueOf(minutes)));

        String joinDate = JOIN_DATE_FORMAT.format(
                Instant.ofEpochMilli(player.getFirstPlayed()).atZone(ZoneId.systemDefault()));
        player.sendMessage(messages.get("playtime.line-join-date", "date", joinDate));

        return true;
    }
}