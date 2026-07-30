package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.EconomyManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.MoneyFormat;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles /money: with no argument, shows the sender's own balance. With
 * {@code add|remove|set <player> <amount>} (requires
 * {@code poppy.money.admin}), adjusts another player's balance — targeted
 * by name via {@link EconomyManager#resolveByName}, which works even if
 * they're currently offline as long as they've joined this server before.
 *
 * <p>Every mutation is clamped to {@code [min-money, max-money]}
 * (config.yml) by {@link EconomyManager} itself, which rejects (rather
 * than silently clamping) any change that would cross either bound.
 */
public class MoneyCommand extends SafeCommand implements TabCompleter {

    private final EconomyManager economy;

    public MoneyCommand(JavaPlugin plugin, EconomyManager economy, Messages messages) {
        super(plugin, messages);
        this.economy = economy;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        if (args.length == 0) {
            Player player = requirePlayer(sender);
            if (player == null) {
                return true;
            }
            player.sendMessage(messages.get("money.balance", "amount", MoneyFormat.format(economy.getBalance(player.getUniqueId()))));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("add") && !sub.equals("remove") && !sub.equals("set")) {
            sender.sendMessage(messages.get("money.usage"));
            return true;
        }

        if (!sender.hasPermission("poppy.money.admin")) {
            sender.sendMessage(messages.get("general.no-permission"));
            return true;
        }

        if (args.length != 3) {
            sender.sendMessage(messages.get("money.admin-usage"));
            return true;
        }

        UUID target = economy.resolveByName(args[1]);
        if (target == null) {
            sender.sendMessage(messages.get("money.admin-target-not-found", "player", args[1]));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(messages.get("money.admin-invalid-amount"));
            return true;
        }

        if (amount < 0) {
            sender.sendMessage(messages.get("money.admin-invalid-amount"));
            return true;
        }

        EconomyManager.Result result = switch (sub) {
            case "add" -> economy.deposit(target, amount);
            case "remove" -> economy.withdraw(target, amount);
            default -> economy.setBalance(target, amount);
        };

        if (result == EconomyManager.Result.WOULD_EXCEED_MAX) {
            sender.sendMessage(messages.get("money.exceeds-max"));
            return true;
        }
        if (result == EconomyManager.Result.WOULD_GO_BELOW_MIN) {
            sender.sendMessage(messages.get("money.below-min"));
            return true;
        }

        String newBalance = MoneyFormat.format(economy.getBalance(target));
        String messageKey = switch (sub) {
            case "add" -> "money.admin-add-success";
            case "remove" -> "money.admin-remove-success";
            default -> "money.admin-set-success";
        };
        sender.sendMessage(messages.get(messageKey, "player", args[1], "amount", MoneyFormat.format(amount), "balance", newBalance));

        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (!sender.hasPermission("poppy.money.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return List.of("add", "remove", "set").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 2) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                    .toList();
        }

        return List.of();
    }
}