package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.EconomyManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.MoneyFormat;
import fr.quentin.poppy.util.PlayerNameSuggestions;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Handles /pay <player> <amount>: transfers money from the sender to
 * another online player. Target resolution checks
 * {@link Player#canSee(Player)} — same reasoning as {@code TpaCommand}:
 * without it, targeting /pay would leak the presence of vanished staff.
 */
public class PayCommand extends SafeCommand implements TabCompleter {

    private final EconomyManager economy;

    public PayCommand(JavaPlugin plugin, EconomyManager economy, Messages messages) {
        super(plugin, messages);
        this.economy = economy;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length != 2) {
            player.sendMessage(messages.get("money.pay-usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !player.canSee(target)) {
            player.sendMessage(messages.get("money.pay-target-not-found", "player", args[0]));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(messages.get("money.pay-self"));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(messages.get("money.pay-invalid-amount"));
            return true;
        }

        if (amount <= 0) {
            player.sendMessage(messages.get("money.pay-invalid-amount"));
            return true;
        }

        EconomyManager.Result result = economy.transfer(player.getUniqueId(), target.getUniqueId(), amount);

        if (result == EconomyManager.Result.WOULD_GO_BELOW_MIN) {
            player.sendMessage(messages.get("money.pay-insufficient-funds"));
            return true;
        }
        if (result == EconomyManager.Result.WOULD_EXCEED_MAX) {
            player.sendMessage(messages.get("money.pay-target-full"));
            return true;
        }

        String amountText = MoneyFormat.format(amount);
        player.sendMessage(messages.get("money.pay-success-sender", "player", target.getName(), "amount", amountText));
        target.sendMessage(messages.get("money.pay-success-receiver", "player", player.getName(), "amount", amountText));

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