package fr.quentin.poppy.commands.msg;

import fr.quentin.poppy.manager.msg.MessageManager;
import fr.quentin.poppy.util.PlayerNameSuggestions;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.List;

/**
 * Handles /msg (aliases: /tell, /w, /whisper) — <player> <message>:
 * sends a private message, each side seeing their own perspective
 * (see messages.yml's msg.outgoing/msg.incoming). Records the
 * conversation in {@link MessageManager} so either side can /reply.
 *
 * <p>Target resolution checks {@link Player#canSee(Player)} — same
 * reasoning as {@code TpaCommand}: without it, /msg (and its tab
 * completion) would leak the presence of vanished staff.
 *
 * <p>Shares a single {@link CooldownStore} instance with
 * {@code ReplyCommand} (passed in, not created here) — a shared pool is
 * what makes the cooldown mean anything: alternating between /msg and
 * /reply against two separate stores would let a player dodge it
 * entirely.
 */
public class MsgCommand extends SafeCommand implements TabCompleter {

    private final MessageManager messageManager;
    private final PoppyConfig config;
    private final CooldownStore cooldown;

    public MsgCommand(JavaPlugin plugin, MessageManager messageManager, Messages messages, PoppyConfig config, CooldownStore cooldown) {
        super(plugin, messages);
        this.messageManager = messageManager;
        this.config = config;
        this.cooldown = cooldown;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(messages.get("msg.usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !player.canSee(target)) {
            player.sendMessage(messages.get("msg.player-not-found", "player", args[0]));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(messages.get("msg.self"));
            return true;
        }

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("msg.cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        cooldown.start(player.getUniqueId(), config.msgCooldownMillis());
        messageManager.recordConversation(player.getUniqueId(), target.getUniqueId());

        player.sendMessage(messages.get("msg.outgoing", "target", target.getName(), "message", text));
        target.sendMessage(messages.get("msg.incoming", "sender", player.getName(), "message", text));

        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        return PlayerNameSuggestions.onlineExcept(player, args[0]);
    }
}