package fr.quentin.poppy;

import fr.quentin.poppy.commands.*;
import fr.quentin.poppy.gui.*;
import fr.quentin.poppy.listeners.*;
import fr.quentin.poppy.manager.*;
import fr.quentin.poppy.util.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Plugin entry point. Every setting except messages.yml's text and the
 * per-world sleep gamerule is read live from {@link PoppyConfig}, so
 * {@code /poppy reload} (see {@link PoppyCommand}) applies everywhere
 * immediately without restarting the server.
 */
public final class Poppy extends JavaPlugin {

    private HomeManager homeManager;
    private SpawnManager spawnManager;
    private PoppyStats stats;
    private PoppyLogger poppyLogger;
    private DeathChestManager deathChestManager;
    private EconomyManager economyManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PoppyConfig config = new PoppyConfig(this);
        homeManager = new HomeManager(this, config);
        spawnManager = new SpawnManager(this);
        economyManager = new EconomyManager(this, config);
        stats = new PoppyStats();

        Messages messages = new Messages(this);
        CooldownRegistry cooldownRegistry = new CooldownRegistry();
        poppyLogger = new PoppyLogger(this, config);

        TpaManager tpaManager = new TpaManager(this, messages, config, poppyLogger);

        HomesGUI homesGUI = new HomesGUI(this, messages);
        ConfirmDeleteGUI confirmDeleteGUI = new ConfirmDeleteGUI(this, messages);
        ConfirmOverwriteGUI confirmOverwriteGUI = new ConfirmOverwriteGUI(this, messages);
        BackManager backManager = new BackManager();
        CombatManager combatManager = new CombatManager(config, cooldownRegistry);
        TeleportManager teleportManager = new TeleportManager(this, messages, config, backManager, combatManager, stats, poppyLogger);
        ShareManager shareManager = new ShareManager(this, config);
        TrashGUI trashGUI = new TrashGUI(messages, config);
        AfkManager afkManager = new AfkManager();
        DeathLocationManager deathLocationManager = new DeathLocationManager();
        deathChestManager = new DeathChestManager(this, messages, config, poppyLogger);
        FlyManager flyManager = new FlyManager(this, messages, config, combatManager);

        ShopManager shopManager = new ShopManager(this, economyManager);
        ShopGUI shopGUI = new ShopGUI(this, messages);

        // Both created here (not inline at registerEvents time) since PoppyCommand
        // needs a reference to each to call reapply() from /poppy reload.
        SleepPercentageListener sleepPercentageListener = new SleepPercentageListener(this, config);
        TabHealthListener tabHealthListener = new TabHealthListener(this, afkManager, config);

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

        ShareHomeCommand shareHomeCommand = new ShareHomeCommand(this, homeManager, shareManager, messages, config, stats, poppyLogger, cooldownRegistry);
        Objects.requireNonNull(getCommand("sharehome")).setExecutor(shareHomeCommand);
        Objects.requireNonNull(getCommand("sharehome")).setTabCompleter(shareHomeCommand);

        Objects.requireNonNull(getCommand("poppygoto")).setExecutor(new PoppyGotoCommand(this, shareManager, homeManager, teleportManager, messages, config, poppyLogger));
        Objects.requireNonNull(getCommand("deathback")).setExecutor(new DeathBackCommand(this, deathLocationManager, teleportManager, messages));

        Objects.requireNonNull(getCommand("back")).setExecutor(new BackCommand(this, backManager, teleportManager, messages));

        RtpCommand rtpCommand = new RtpCommand(this, teleportManager, messages, config, stats, cooldownRegistry);
        Objects.requireNonNull(getCommand("rtp")).setExecutor(rtpCommand);
        getServer().getPluginManager().registerEvents(rtpCommand, this);

        Objects.requireNonNull(getCommand("trash")).setExecutor(new TrashCommand(this, trashGUI, messages));

        Objects.requireNonNull(getCommand("afk")).setExecutor(new AfkCommand(this, afkManager, messages, config, poppyLogger));

        TpaCommand tpaCommand = new TpaCommand(this, tpaManager, messages, poppyLogger);
        Objects.requireNonNull(getCommand("tpa")).setExecutor(tpaCommand);
        Objects.requireNonNull(getCommand("tpa")).setTabCompleter(tpaCommand);

        TpaHereCommand tpaHereCommand = new TpaHereCommand(this, tpaManager, messages, poppyLogger);
        Objects.requireNonNull(getCommand("tpahere")).setExecutor(tpaHereCommand);
        Objects.requireNonNull(getCommand("tpahere")).setTabCompleter(tpaHereCommand);

        TpaAcceptCommand tpaAcceptCommand = new TpaAcceptCommand(this, tpaManager, teleportManager, combatManager, messages, config, poppyLogger);
        Objects.requireNonNull(getCommand("tpaccept")).setExecutor(tpaAcceptCommand);
        Objects.requireNonNull(getCommand("tpaccept")).setTabCompleter(tpaAcceptCommand);

        TpaDenyCommand tpaDenyCommand = new TpaDenyCommand(this, tpaManager, messages, poppyLogger);
        Objects.requireNonNull(getCommand("tpadeny")).setExecutor(tpaDenyCommand);
        Objects.requireNonNull(getCommand("tpadeny")).setTabCompleter(tpaDenyCommand);

        TpaCancelCommand tpaCancelCommand = new TpaCancelCommand(this, tpaManager, messages, poppyLogger);
        Objects.requireNonNull(getCommand("tpacancel")).setExecutor(tpaCancelCommand);
        Objects.requireNonNull(getCommand("tpacancel")).setTabCompleter(tpaCancelCommand);

        TpaToggleCommand tpaToggleCommand = new TpaToggleCommand(this, tpaManager, messages, config);
        Objects.requireNonNull(getCommand("tpatoggle")).setExecutor(tpaToggleCommand);

        PoppyCommand poppyCommand = new PoppyCommand(this, messages, config, poppyLogger, sleepPercentageListener, tabHealthListener, shopManager);
        Objects.requireNonNull(getCommand("poppy")).setExecutor(poppyCommand);
        Objects.requireNonNull(getCommand("poppy")).setTabCompleter(poppyCommand);

        Objects.requireNonNull(getCommand("feed")).setExecutor(new FeedCommand(this, messages, config, cooldownRegistry));
        Objects.requireNonNull(getCommand("heal")).setExecutor(new HealCommand(this, messages, config, cooldownRegistry));

        Objects.requireNonNull(getCommand("fly")).setExecutor(new FlyCommand(this, flyManager, messages));
        Objects.requireNonNull(getCommand("flytime")).setExecutor(new FlyTimeCommand(this, flyManager, messages));

        Objects.requireNonNull(getCommand("combat")).setExecutor(new CombatCommand(this, combatManager, messages));

        Objects.requireNonNull(getCommand("cooldowns")).setExecutor(new CooldownsCommand(this, messages, cooldownRegistry));

        MoneyCommand moneyCommand = new MoneyCommand(this, economyManager, messages);
        Objects.requireNonNull(getCommand("money")).setExecutor(moneyCommand);
        Objects.requireNonNull(getCommand("money")).setTabCompleter(moneyCommand);

        PayCommand payCommand = new PayCommand(this, economyManager, messages);
        Objects.requireNonNull(getCommand("pay")).setExecutor(payCommand);
        Objects.requireNonNull(getCommand("pay")).setTabCompleter(payCommand);

        Objects.requireNonNull(getCommand("baltop")).setExecutor(new BalTopCommand(this, economyManager, messages, config.economyBaltopSize()));

        Objects.requireNonNull(getCommand("shop")).setExecutor(new ShopCommand(this, shopManager, shopGUI, messages));

        getServer().getPluginManager().registerEvents(
                new HomesGUIListener(this, homeManager, homesGUI, confirmDeleteGUI, confirmOverwriteGUI, teleportManager, messages, stats, poppyLogger), this);
        getServer().getPluginManager().registerEvents(teleportManager, this);
        getServer().getPluginManager().registerEvents(new BackListener(this, backManager, config), this);
        getServer().getPluginManager().registerEvents(new TrashListener(this, messages, poppyLogger), this);
        getServer().getPluginManager().registerEvents(new JoinQuitListener(this, messages, config), this);
        getServer().getPluginManager().registerEvents(tabHealthListener, this);
        getServer().getPluginManager().registerEvents(new AfkListener(this, afkManager, messages, poppyLogger), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this, combatManager, config, poppyLogger, messages, deathChestManager, backManager, deathLocationManager), this);
        getServer().getPluginManager().registerEvents(new UnknownCommandListener(this, messages, config), this);
        getServer().getPluginManager().registerEvents(new HomeCacheListener(this, homeManager), this);
        getServer().getPluginManager().registerEvents(new TpaQuitListener(tpaManager, messages), this);
        getServer().getPluginManager().registerEvents(new PoppyLoreListener(this, messages, config, poppyLogger), this);
        getServer().getPluginManager().registerEvents(new DeathCoordsListener(this, messages, deathLocationManager, config, poppyLogger), this);
        getServer().getPluginManager().registerEvents(deathChestManager, this);
        getServer().getPluginManager().registerEvents(sleepPercentageListener, this);
        getServer().getPluginManager().registerEvents(new SleepStatusListener(this, messages, config, poppyLogger), this);
        getServer().getPluginManager().registerEvents(new SilkSpawnerListener(this, messages, config), this);
        getServer().getPluginManager().registerEvents(flyManager, this);
        getServer().getPluginManager().registerEvents(economyManager, this);
        getServer().getPluginManager().registerEvents(new ShopListener(this, shopManager, shopGUI, messages), this);

        // Always scheduled now (rather than only if afk-auto-enabled at startup) —
        // AutoAfkTask checks the setting live each run, so it can be toggled via
        // /poppy reload without a restart.
        new AutoAfkTask(this, afkManager, messages, config, poppyLogger).runTaskTimer(this, 20L * 60, 20L * 60);

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
        if (deathChestManager != null) {
            deathChestManager.shutdown();
        }
        if (economyManager != null) {
            economyManager.shutdown();
        }

        logShutdownSummary();
        getLogger().info("Poppy has been disabled.");

        if (poppyLogger != null) {
            poppyLogger.shutdown();
        }
    }

    private void logStartupBanner() {
        boolean showStats = getConfig().getBoolean("startup-stats-enabled", true);

        getLogger().info("========================================");
        getLogger().info(" Poppy v" + getPluginMeta().getVersion() + " enabled successfully.");
        getLogger().info(" config.yml and messages.yml loaded OK.");
        getLogger().info(" Spawn point: " + (spawnManager.hasSpawn() ? "configured" : "NOT SET (use /setspawn)"));
        getLogger().info(" Modules: afk-auto=" + getConfig().getBoolean("afk-auto-enabled", true)
                + ", combat-tag=" + getConfig().getBoolean("combat-tag-enabled", true)
                + ", tab-health=" + getConfig().getBoolean("show-health-in-tab", true)
                + ", custom-join-quit=" + getConfig().getBoolean("custom-join-message", true));
        getLogger().info("========================================");

        if (showStats) {
            homeManager.collectStatsAsync().thenAccept(stats ->
                    getLogger().info(" Known players with homes: " + stats.playersWithHomes() + " (" + stats.totalHomes() + " homes total)")
            );
        }
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