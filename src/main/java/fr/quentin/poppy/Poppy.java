package fr.quentin.poppy;

import fr.quentin.poppy.commands.*;
import fr.quentin.poppy.gui.ConfirmDeleteGUI;
import fr.quentin.poppy.gui.ConfirmOverwriteGUI;
import fr.quentin.poppy.gui.HomesGUI;
import fr.quentin.poppy.gui.HomesGUIListener;
import fr.quentin.poppy.gui.TrashGUI;
import fr.quentin.poppy.gui.TrashListener;
import fr.quentin.poppy.listeners.*;
import fr.quentin.poppy.manager.*;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.PoppyStats;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Plugin entry point: wires every manager, GUI, command, and listener
 * together in {@link #onEnable}, and flushes homes/spawn to disk in
 * {@link #onDisable}.
 *
 * <p>Note that Bukkit fires {@link org.bukkit.event.player.PlayerQuitEvent}
 * for every online player before {@code onDisable} runs, so by the time
 * {@link #onDisable} calls {@link HomeManager#saveAllSync()},
 * {@link HomeCacheListener} has typically already flushed and evicted every
 * player's cache entry — {@code saveAllSync} is a safety net for anything
 * left over (e.g. after an abnormal shutdown), not the primary save path.
 */
public final class Poppy extends JavaPlugin {

    private HomeManager homeManager;
    private SpawnManager spawnManager;
    private PoppyStats stats;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        homeManager = new HomeManager(this);
        spawnManager = new SpawnManager(this);
        stats = new PoppyStats();

        Messages messages = new Messages(this);

        // PoppyLogger only needs the plugin/config, so it's created early —
        // every manager/command below that logs activity depends on it.
        PoppyLogger poppyLogger = new PoppyLogger(this);

        // TpaManager needs `messages` and `poppyLogger` (it sends expiry
        // notifications and logs expirations itself), so it's created here,
        // after both, instead of alongside homeManager/spawnManager above.
        TpaManager tpaManager = new TpaManager(this, messages, poppyLogger);

        HomesGUI homesGUI = new HomesGUI(this, messages);
        ConfirmDeleteGUI confirmDeleteGUI = new ConfirmDeleteGUI(this, messages);
        ConfirmOverwriteGUI confirmOverwriteGUI = new ConfirmOverwriteGUI(this, messages);
        BackManager backManager = new BackManager();
        CombatManager combatManager = new CombatManager(getConfig().getLong("combat-tag-seconds", 10));
        TeleportManager teleportManager = new TeleportManager(this, messages, backManager, combatManager, stats, poppyLogger);
        ShareManager shareManager = new ShareManager(this);
        TrashGUI trashGUI = new TrashGUI(this, messages);
        AfkManager afkManager = new AfkManager();
        DeathLocationManager deathLocationManager = new DeathLocationManager();
        DeathChestManager deathChestManager = new DeathChestManager(this, messages, poppyLogger);

        Objects.requireNonNull(getCommand("sethome")).setExecutor(
                new SetHomeCommand(this, homeManager, confirmOverwriteGUI, messages, stats, poppyLogger));

        HomeCommand homeCommand = new HomeCommand(this, homeManager, homesGUI, teleportManager, messages);
        Objects.requireNonNull(getCommand("home")).setExecutor(homeCommand);
        Objects.requireNonNull(getCommand("home")).setTabCompleter(homeCommand);

        DelHomeCommand delHomeCommand = new DelHomeCommand(this, homeManager, messages, stats, poppyLogger);
        Objects.requireNonNull(getCommand("delhome")).setExecutor(delHomeCommand);
        Objects.requireNonNull(getCommand("delhome")).setTabCompleter(delHomeCommand);

        Objects.requireNonNull(getCommand("homes")).setExecutor(new HomesCommand(this, homeManager, homesGUI, messages));

        Objects.requireNonNull(getCommand("setspawn")).setExecutor(new SetSpawnCommand(this, spawnManager, messages, poppyLogger));
        Objects.requireNonNull(getCommand("delspawn")).setExecutor(new DelSpawnCommand(this, spawnManager, messages, poppyLogger));
        Objects.requireNonNull(getCommand("spawn")).setExecutor(new SpawnCommand(this, spawnManager, teleportManager, messages));

        ShareHomeCommand shareHomeCommand = new ShareHomeCommand(this, homeManager, shareManager, messages, stats, poppyLogger);
        Objects.requireNonNull(getCommand("sharehome")).setExecutor(shareHomeCommand);
        Objects.requireNonNull(getCommand("sharehome")).setTabCompleter(shareHomeCommand);

        Objects.requireNonNull(getCommand("poppygoto")).setExecutor(new PoppyGotoCommand(this, shareManager, teleportManager, messages));
        Objects.requireNonNull(getCommand("deathback")).setExecutor(new DeathBackCommand(this, deathLocationManager, teleportManager, messages));

        Objects.requireNonNull(getCommand("back")).setExecutor(new BackCommand(this, backManager, teleportManager, messages));

        RtpCommand rtpCommand = new RtpCommand(this, teleportManager, messages, stats);
        Objects.requireNonNull(getCommand("rtp")).setExecutor(rtpCommand);
        getServer().getPluginManager().registerEvents(rtpCommand, this);

        Objects.requireNonNull(getCommand("trash")).setExecutor(new TrashCommand(this, trashGUI, messages));

        Objects.requireNonNull(getCommand("afk")).setExecutor(new AfkCommand(this, afkManager, messages, poppyLogger));

        TpaCommand tpaCommand = new TpaCommand(this, tpaManager, messages, poppyLogger);
        Objects.requireNonNull(getCommand("tpa")).setExecutor(tpaCommand);
        Objects.requireNonNull(getCommand("tpa")).setTabCompleter(tpaCommand);

        TpaHereCommand tpaHereCommand = new TpaHereCommand(this, tpaManager, messages, poppyLogger);
        Objects.requireNonNull(getCommand("tpahere")).setExecutor(tpaHereCommand);
        Objects.requireNonNull(getCommand("tpahere")).setTabCompleter(tpaHereCommand);

        TpaAcceptCommand tpaAcceptCommand = new TpaAcceptCommand(this, tpaManager, teleportManager, messages, poppyLogger);
        Objects.requireNonNull(getCommand("tpaccept")).setExecutor(tpaAcceptCommand);
        Objects.requireNonNull(getCommand("tpaccept")).setTabCompleter(tpaAcceptCommand);

        TpaDenyCommand tpaDenyCommand = new TpaDenyCommand(this, tpaManager, messages, poppyLogger);
        Objects.requireNonNull(getCommand("tpadeny")).setExecutor(tpaDenyCommand);
        Objects.requireNonNull(getCommand("tpadeny")).setTabCompleter(tpaDenyCommand);

        TpaCancelCommand tpaCancelCommand = new TpaCancelCommand(this, tpaManager, messages, poppyLogger);
        Objects.requireNonNull(getCommand("tpacancel")).setExecutor(tpaCancelCommand);
        Objects.requireNonNull(getCommand("tpacancel")).setTabCompleter(tpaCancelCommand);

        Objects.requireNonNull(getCommand("poppy")).setExecutor(new PoppyCommand(this, messages, poppyLogger));

        getServer().getPluginManager().registerEvents(
                new HomesGUIListener(this, homeManager, homesGUI, confirmDeleteGUI, confirmOverwriteGUI, teleportManager, messages, stats, poppyLogger), this);
        getServer().getPluginManager().registerEvents(teleportManager, this);
        getServer().getPluginManager().registerEvents(new BackListener(this, backManager), this);
        getServer().getPluginManager().registerEvents(new TrashListener(this, messages, poppyLogger), this);
        getServer().getPluginManager().registerEvents(new JoinQuitListener(this, messages), this);
        getServer().getPluginManager().registerEvents(new TabHealthListener(this, afkManager), this);
        getServer().getPluginManager().registerEvents(new AfkListener(this, afkManager, messages, poppyLogger), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this, combatManager, poppyLogger), this);
        getServer().getPluginManager().registerEvents(new UnknownCommandListener(this, messages), this);
        getServer().getPluginManager().registerEvents(new HomeCacheListener(this, homeManager), this);
        getServer().getPluginManager().registerEvents(shareHomeCommand, this);
        getServer().getPluginManager().registerEvents(new TpaQuitListener(tpaManager), this);
        getServer().getPluginManager().registerEvents(new PoppyLoreListener(this, messages, poppyLogger), this);
        getServer().getPluginManager().registerEvents(new DeathCoordsListener(this, messages, deathLocationManager, poppyLogger), this);
        getServer().getPluginManager().registerEvents(deathChestManager, this);
        getServer().getPluginManager().registerEvents(new SleepPercentageListener(this), this);
        getServer().getPluginManager().registerEvents(new SleepStatusListener(this, messages, poppyLogger), this);

        if (getConfig().getBoolean("afk-auto-enabled", true)) {
            new AutoAfkTask(this, afkManager, messages, poppyLogger).runTaskTimer(this, 20L * 60, 20L * 60);
        }

        logStartupBanner();
    }

    @Override
    public void onDisable() {
        if (homeManager != null) {
            homeManager.saveAllSync();
        }
        if (spawnManager != null) {
            spawnManager.saveSync();
        }

        logShutdownSummary();
        getLogger().info("Poppy has been disabled.");
    }

    private void logStartupBanner() {
        boolean showStats = getConfig().getBoolean("startup-stats-enabled", true);

        getLogger().info("========================================");
        getLogger().info(" Poppy v" + getPluginMeta().getVersion() + " enabled successfully.");
        getLogger().info(" config.yml and messages.yml loaded OK.");
        getLogger().info(" Spawn point: " + (spawnManager.hasSpawn() ? "configured" : "NOT SET (use /setspawn)"));

        if (showStats) {
            int playersWithHomes = homeManager.countPlayersWithHomes();
            int totalHomes = homeManager.countTotalHomes();
            getLogger().info(" Known players with homes: " + playersWithHomes + " (" + totalHomes + " homes total)");
        }

        getLogger().info(" Modules: afk-auto=" + getConfig().getBoolean("afk-auto-enabled", true)
                + ", combat-tag=" + getConfig().getBoolean("combat-tag-enabled", true)
                + ", tab-health=" + getConfig().getBoolean("show-health-in-tab", true)
                + ", custom-join-quit=" + getConfig().getBoolean("custom-join-message", true));
        getLogger().info("========================================");
    }

    private void logShutdownSummary() {
        if (stats == null) {
            return;
        }

        getLogger().info("---- Poppy session summary ----");
        getLogger().info(" Homes created: " + stats.getHomesCreated());
        getLogger().info(" Homes deleted: " + stats.getHomesDeleted());
        getLogger().info(" Teleports performed: " + stats.getTeleportsPerformed());
        getLogger().info(" /rtp uses: " + stats.getRtpUsed());
        getLogger().info(" Homes shared: " + stats.getSharesCreated());
        getLogger().info("--------------------------------");
    }
}