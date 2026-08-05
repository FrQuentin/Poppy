package fr.quentin.poppy;

import fr.quentin.poppy.commands.afk.AfkCommand;
import fr.quentin.poppy.commands.back.BackCommand;
import fr.quentin.poppy.commands.back.DeathBackCommand;
import fr.quentin.poppy.commands.combat.CombatCommand;
import fr.quentin.poppy.commands.fly.FlyCommand;
import fr.quentin.poppy.commands.fly.FlyTimeCommand;
import fr.quentin.poppy.commands.home.DelHomeCommand;
import fr.quentin.poppy.commands.home.HomeCommand;
import fr.quentin.poppy.commands.home.HomesCommand;
import fr.quentin.poppy.commands.home.SetHomeCommand;
import fr.quentin.poppy.commands.misc.CooldownsCommand;
import fr.quentin.poppy.commands.misc.FeedCommand;
import fr.quentin.poppy.commands.misc.HealCommand;
import fr.quentin.poppy.commands.misc.PlaytimeCommand;
import fr.quentin.poppy.commands.misc.PoppyCommand;
import fr.quentin.poppy.commands.msg.MsgCommand;
import fr.quentin.poppy.commands.msg.ReplyCommand;
import fr.quentin.poppy.commands.rtp.RtpCommand;
import fr.quentin.poppy.commands.share.PoppyGotoCommand;
import fr.quentin.poppy.commands.share.ShareHomeCommand;
import fr.quentin.poppy.commands.spawn.DelSpawnCommand;
import fr.quentin.poppy.commands.spawn.SetSpawnCommand;
import fr.quentin.poppy.commands.spawn.SpawnCommand;
import fr.quentin.poppy.commands.tpa.*;
import fr.quentin.poppy.commands.trash.TrashCommand;
import fr.quentin.poppy.gui.home.ConfirmDeleteGUI;
import fr.quentin.poppy.gui.home.ConfirmOverwriteGUI;
import fr.quentin.poppy.gui.home.HomesGUI;
import fr.quentin.poppy.gui.home.HomesGUIListener;
import fr.quentin.poppy.gui.trash.TrashGUI;
import fr.quentin.poppy.gui.trash.TrashListener;
import fr.quentin.poppy.listeners.afk.AfkListener;
import fr.quentin.poppy.listeners.death.DeathCoordsListener;
import fr.quentin.poppy.listeners.misc.ChatFormatListener;
import fr.quentin.poppy.listeners.misc.JoinQuitListener;
import fr.quentin.poppy.listeners.misc.UnknownCommandListener;
import fr.quentin.poppy.listeners.poppy.PoppyLoreListener;
import fr.quentin.poppy.listeners.silkspawner.SilkSpawnerListener;
import fr.quentin.poppy.listeners.sleep.SleepStatusListener;
import fr.quentin.poppy.listeners.tab.TabHealthListener;
import fr.quentin.poppy.manager.afk.AfkManager;
import fr.quentin.poppy.manager.afk.AutoAfkTask;
import fr.quentin.poppy.manager.back.BackListener;
import fr.quentin.poppy.manager.back.BackManager;
import fr.quentin.poppy.manager.combat.CombatListener;
import fr.quentin.poppy.manager.combat.CombatManager;
import fr.quentin.poppy.manager.death.DeathLocationManager;
import fr.quentin.poppy.manager.deathchest.DeathChestManager;
import fr.quentin.poppy.manager.fly.FlyManager;
import fr.quentin.poppy.manager.home.HomeCacheListener;
import fr.quentin.poppy.manager.home.HomeManager;
import fr.quentin.poppy.manager.msg.MessageManager;
import fr.quentin.poppy.manager.playtime.PlaytimeManager;
import fr.quentin.poppy.manager.share.ShareManager;
import fr.quentin.poppy.manager.sleep.SleepPercentageListener;
import fr.quentin.poppy.manager.spawn.SpawnManager;
import fr.quentin.poppy.manager.teleport.TeleportManager;
import fr.quentin.poppy.manager.tpa.TpaManager;
import fr.quentin.poppy.manager.tpa.TpaQuitListener;
import fr.quentin.poppy.util.*;
import fr.quentin.poppy.util.cooldown.CooldownManager;
import fr.quentin.poppy.util.cooldown.CooldownRegistry;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Plugin entry point. Every setting except messages.yml's text and the
 * per-world sleep gamerule is read live from {@link PoppyConfig}, so
 * {@code /poppy reload} (see {@link PoppyCommand}) applies everywhere
 * immediately without restarting the server.
 *
 * <p>Every cooldown in the plugin is created through a single
 * {@link CooldownManager}, created here right after {@link CooldownRegistry}
 * — no other class instantiates {@link CooldownStore} directly.
 */
public final class Poppy extends JavaPlugin {

    private HomeManager homeManager;
    private SpawnManager spawnManager;
    private PoppyLogger poppyLogger;
    private DeathChestManager deathChestManager;
    private FlyManager flyManager;
    private PlaytimeManager playtimeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PoppyConfig config = new PoppyConfig(this);
        homeManager = new HomeManager(this, config);
        spawnManager = new SpawnManager(this);
        PoppyStats stats = new PoppyStats();

        Messages messages = new Messages(this);
        CooldownRegistry cooldownRegistry = new CooldownRegistry();
        CooldownManager cooldownManager = new CooldownManager(this, cooldownRegistry);
        poppyLogger = new PoppyLogger(this, config);
        playtimeManager = new PlaytimeManager(this);

        TpaManager tpaManager = new TpaManager(this, messages, config, poppyLogger, cooldownManager);

        HomesGUI homesGUI = new HomesGUI(this, messages);
        ConfirmDeleteGUI confirmDeleteGUI = new ConfirmDeleteGUI(this, messages);
        ConfirmOverwriteGUI confirmOverwriteGUI = new ConfirmOverwriteGUI(this, messages);
        BackManager backManager = new BackManager();
        CombatManager combatManager = new CombatManager(config, cooldownManager);
        TeleportManager teleportManager = new TeleportManager(this, messages, config, backManager, combatManager, stats, poppyLogger);
        ShareManager shareManager = new ShareManager(this, config);
        TrashGUI trashGUI = new TrashGUI(messages, config);
        AfkManager afkManager = new AfkManager();
        DeathLocationManager deathLocationManager = new DeathLocationManager();
        deathChestManager = new DeathChestManager(this, messages, config, poppyLogger);
        flyManager = new FlyManager(this, messages, config, combatManager, cooldownManager);
        MessageManager messageManager = new MessageManager();
        CooldownStore msgCooldown = cooldownManager.get("msg", "Message");

        // Both created here (not inline at registerEvents time) since PoppyCommand
        // needs a reference to each to call reapply() from /poppy reload.
        SleepPercentageListener sleepPercentageListener = new SleepPercentageListener(this, config);
        TabHealthListener tabHealthListener = new TabHealthListener(this, afkManager, config, messages);

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

        ShareHomeCommand shareHomeCommand = new ShareHomeCommand(this, homeManager, shareManager, messages, config, stats, poppyLogger, cooldownManager);
        Objects.requireNonNull(getCommand("sharehome")).setExecutor(shareHomeCommand);
        Objects.requireNonNull(getCommand("sharehome")).setTabCompleter(shareHomeCommand);

        Objects.requireNonNull(getCommand("poppygoto")).setExecutor(
                new PoppyGotoCommand(this, shareManager, homeManager, teleportManager, messages, config, poppyLogger, cooldownManager));
        Objects.requireNonNull(getCommand("deathback")).setExecutor(new DeathBackCommand(this, deathLocationManager, teleportManager, messages));

        Objects.requireNonNull(getCommand("back")).setExecutor(new BackCommand(this, backManager, teleportManager, messages));

        RtpCommand rtpCommand = new RtpCommand(this, teleportManager, combatManager, messages, config, stats, cooldownManager);
        Objects.requireNonNull(getCommand("rtp")).setExecutor(rtpCommand);
        getServer().getPluginManager().registerEvents(rtpCommand, this);

        Objects.requireNonNull(getCommand("trash")).setExecutor(new TrashCommand(this, trashGUI, messages));

        Objects.requireNonNull(getCommand("afk")).setExecutor(new AfkCommand(this, afkManager, messages, config, poppyLogger, cooldownManager));

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

        TpaToggleCommand tpaToggleCommand = new TpaToggleCommand(this, tpaManager, messages, config, cooldownManager);
        Objects.requireNonNull(getCommand("tpatoggle")).setExecutor(tpaToggleCommand);

        PoppyCommand poppyCommand = new PoppyCommand(this, messages, config, poppyLogger, sleepPercentageListener, tabHealthListener, cooldownManager);
        Objects.requireNonNull(getCommand("poppy")).setExecutor(poppyCommand);
        Objects.requireNonNull(getCommand("poppy")).setTabCompleter(poppyCommand);

        Objects.requireNonNull(getCommand("feed")).setExecutor(new FeedCommand(this, messages, config, cooldownManager));
        Objects.requireNonNull(getCommand("heal")).setExecutor(new HealCommand(this, messages, config, cooldownManager));

        Objects.requireNonNull(getCommand("fly")).setExecutor(new FlyCommand(this, flyManager, messages));
        Objects.requireNonNull(getCommand("flytime")).setExecutor(new FlyTimeCommand(this, flyManager, messages));

        Objects.requireNonNull(getCommand("combat")).setExecutor(new CombatCommand(this, combatManager, messages));

        Objects.requireNonNull(getCommand("cooldowns")).setExecutor(new CooldownsCommand(this, messages, cooldownRegistry));

        Objects.requireNonNull(getCommand("playtime")).setExecutor(new PlaytimeCommand(this, playtimeManager, messages));

        MsgCommand msgCommand = new MsgCommand(this, messageManager, messages, config, msgCooldown);
        Objects.requireNonNull(getCommand("msg")).setExecutor(msgCommand);
        Objects.requireNonNull(getCommand("msg")).setTabCompleter(msgCommand);

        Objects.requireNonNull(getCommand("reply")).setExecutor(new ReplyCommand(this, messageManager, messages, config, msgCooldown));

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
        getServer().getPluginManager().registerEvents(new PoppyLoreListener(this, messages, config, poppyLogger, cooldownManager), this);
        getServer().getPluginManager().registerEvents(new DeathCoordsListener(this, messages, deathLocationManager, config, poppyLogger), this);
        getServer().getPluginManager().registerEvents(deathChestManager, this);
        getServer().getPluginManager().registerEvents(sleepPercentageListener, this);
        getServer().getPluginManager().registerEvents(new SleepStatusListener(this, messages, config, poppyLogger), this);
        getServer().getPluginManager().registerEvents(new SilkSpawnerListener(this, messages, config), this);
        getServer().getPluginManager().registerEvents(flyManager, this);
        getServer().getPluginManager().registerEvents(playtimeManager, this);
        getServer().getPluginManager().registerEvents(messageManager, this);
        getServer().getPluginManager().registerEvents(new ChatFormatListener(), this);

        // Always scheduled now (rather than only if afk-auto-enabled at startup) —
        // AutoAfkTask checks the setting live each run, so it can be toggled via
        // /poppy reload without a restart.
        new AutoAfkTask(this, afkManager, messages, config, poppyLogger).runTaskTimer(this, 20L * 60, 20L * 60);
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
        if (flyManager != null) {
            flyManager.shutdown();
        }
        if (playtimeManager != null) {
            playtimeManager.shutdown();
        }

        getLogger().info("Poppy has been disabled.");

        if (poppyLogger != null) {
            poppyLogger.shutdown();
        }
    }
}