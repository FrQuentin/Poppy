package fr.quentin.poppy;

import fr.quentin.poppy.commands.AfkCommand;
import fr.quentin.poppy.commands.BackCommand;
import fr.quentin.poppy.commands.DelHomeCommand;
import fr.quentin.poppy.commands.HomeCommand;
import fr.quentin.poppy.commands.HomesCommand;
import fr.quentin.poppy.commands.PoppyGotoCommand;
import fr.quentin.poppy.commands.RtpCommand;
import fr.quentin.poppy.commands.SetHomeCommand;
import fr.quentin.poppy.commands.SetSpawnCommand;
import fr.quentin.poppy.commands.ShareHomeCommand;
import fr.quentin.poppy.commands.SpawnCommand;
import fr.quentin.poppy.commands.TrashCommand;
import fr.quentin.poppy.gui.ConfirmDeleteGUI;
import fr.quentin.poppy.gui.ConfirmOverwriteGUI;
import fr.quentin.poppy.gui.HomesGUI;
import fr.quentin.poppy.gui.HomesGUIListener;
import fr.quentin.poppy.gui.TrashGUI;
import fr.quentin.poppy.gui.TrashListener;
import fr.quentin.poppy.listeners.JoinQuitListener;
import fr.quentin.poppy.listeners.TabHealthListener;
import fr.quentin.poppy.listeners.UnknownCommandListener;
import fr.quentin.poppy.manager.AfkListener;
import fr.quentin.poppy.manager.AfkManager;
import fr.quentin.poppy.manager.AutoAfkTask;
import fr.quentin.poppy.manager.BackListener;
import fr.quentin.poppy.manager.BackManager;
import fr.quentin.poppy.manager.CombatListener;
import fr.quentin.poppy.manager.CombatManager;
import fr.quentin.poppy.manager.HomeCacheListener;
import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.manager.SpawnManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyStats;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

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
        HomesGUI homesGUI = new HomesGUI(this, messages);
        ConfirmDeleteGUI confirmDeleteGUI = new ConfirmDeleteGUI(this, messages);
        ConfirmOverwriteGUI confirmOverwriteGUI = new ConfirmOverwriteGUI(this, messages);
        BackManager backManager = new BackManager();
        CombatManager combatManager = new CombatManager(getConfig().getLong("combat-tag-seconds", 10));
        TeleportManager teleportManager = new TeleportManager(this, messages, backManager, combatManager, stats);
        ShareManager shareManager = new ShareManager(this);
        TrashGUI trashGUI = new TrashGUI(this, messages);
        AfkManager afkManager = new AfkManager();

        Objects.requireNonNull(getCommand("sethome")).setExecutor(
                new SetHomeCommand(this, homeManager, confirmOverwriteGUI, messages, stats));

        HomeCommand homeCommand = new HomeCommand(this, homeManager, homesGUI, teleportManager, messages);
        Objects.requireNonNull(getCommand("home")).setExecutor(homeCommand);
        Objects.requireNonNull(getCommand("home")).setTabCompleter(homeCommand);

        DelHomeCommand delHomeCommand = new DelHomeCommand(this, homeManager, messages, stats);
        Objects.requireNonNull(getCommand("delhome")).setExecutor(delHomeCommand);
        Objects.requireNonNull(getCommand("delhome")).setTabCompleter(delHomeCommand);

        Objects.requireNonNull(getCommand("homes")).setExecutor(new HomesCommand(this, homeManager, homesGUI, messages));

        Objects.requireNonNull(getCommand("setspawn")).setExecutor(new SetSpawnCommand(this, spawnManager, messages));
        Objects.requireNonNull(getCommand("spawn")).setExecutor(new SpawnCommand(this, spawnManager, teleportManager, messages));

        ShareHomeCommand shareHomeCommand = new ShareHomeCommand(this, homeManager, shareManager, messages, stats);
        Objects.requireNonNull(getCommand("sharehome")).setExecutor(shareHomeCommand);
        Objects.requireNonNull(getCommand("sharehome")).setTabCompleter(shareHomeCommand);
        Objects.requireNonNull(getCommand("poppygoto")).setExecutor(new PoppyGotoCommand(this, shareManager, teleportManager, messages));

        Objects.requireNonNull(getCommand("back")).setExecutor(new BackCommand(this, backManager, teleportManager, messages));

        RtpCommand rtpCommand = new RtpCommand(this, teleportManager, messages, stats);
        Objects.requireNonNull(getCommand("rtp")).setExecutor(rtpCommand);
        getServer().getPluginManager().registerEvents(rtpCommand, this);

        Objects.requireNonNull(getCommand("trash")).setExecutor(new TrashCommand(this, trashGUI, messages));

        Objects.requireNonNull(getCommand("afk")).setExecutor(new AfkCommand(this, afkManager, messages));

        getServer().getPluginManager().registerEvents(
                new HomesGUIListener(this, homeManager, homesGUI, confirmDeleteGUI, confirmOverwriteGUI, teleportManager, messages, stats), this);
        getServer().getPluginManager().registerEvents(teleportManager, this);
        getServer().getPluginManager().registerEvents(new BackListener(this, backManager), this);
        getServer().getPluginManager().registerEvents(new TrashListener(this, messages), this);
        getServer().getPluginManager().registerEvents(new JoinQuitListener(this, messages), this);
        getServer().getPluginManager().registerEvents(new TabHealthListener(this, afkManager), this);
        getServer().getPluginManager().registerEvents(new AfkListener(this, afkManager, messages), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this, combatManager), this);
        getServer().getPluginManager().registerEvents(new UnknownCommandListener(this, messages), this);
        getServer().getPluginManager().registerEvents(new HomeCacheListener(homeManager), this);

        if (getConfig().getBoolean("afk-auto-enabled", true)) {
            new AutoAfkTask(this, afkManager, messages).runTaskTimer(this, 20L * 60, 20L * 60);
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