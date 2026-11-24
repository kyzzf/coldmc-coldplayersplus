package me.bill.fakePlayerPlugin.coldmc;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;

/**
 * ColdMC: bots that come and go on their own, and alts that follow a real player.
 * Replaces the two "botter" Skripts (aliasactive.sk, aliasplayerjoins.sk). the owner, 2026-11-13.
 *
 * <ul>
 *   <li><b>Rotation</b>: every so often one bot from a name list joins, stays a random time,
 *       leaves, and the server is quiet for a random time before the next one.</li>
 *   <li><b>Aliases</b>: when a listed real player joins, their alt joins too. When they leave,
 *       the alt leaves a set time later, unless they came back. {@code /ajoin} (or
 *       {@code /cpp alias}) brings your alt in by hand.</li>
 * </ul>
 * Settings live in {@code auto-join.yml}, its own file so the config migrator never touches it.
 */
public final class AutoJoin implements Listener, CommandExecutor {
    private static final String REASON = "coldmc_autojoin";

    private final FakePlayerPlugin plugin;
    private YamlConfiguration cfg = new YamlConfiguration();
    private final List<BukkitTask> rotationTasks = new ArrayList<>();
    private final Map<String, BukkitTask> aliasLeaveTasks = new HashMap<>();   // real player (lower case) -> pending despawn

    public AutoJoin(FakePlayerPlugin plugin) { this.plugin = plugin; }

    private FakePlayerManager manager() { return plugin.getFakePlayerManager(); }

    // ------------------------------------------------------------------ lifecycle

    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("ajoin");
        if (cmd != null) cmd.setExecutor(this);
        reload();
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        stopTasks();
        aliasLeaveTasks.values().forEach(BukkitTask::cancel); aliasLeaveTasks.clear();
    }

    public void reload() {
        stopTasks();
        File f = new File(plugin.getDataFolder(), "auto-join.yml");
        if (!f.exists()) plugin.saveResource("auto-join.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
        int loops = Math.max(0, cfg.getInt("rotation.at-once", 1));
        if (cfg.getBoolean("rotation.enabled", false) && !names().isEmpty())
            for (int i = 0; i < loops; i++) startRotationLoop();
        plugin.getLogger().info("[auto-join] rotation " + (cfg.getBoolean("rotation.enabled", false) ? loops + " at once, " + names().size() + " names" : "off")
                + ", aliases " + (cfg.getBoolean("aliases.enabled", false) ? aliases().size() + " players" : "off"));
    }

    private void stopTasks() {
        rotationTasks.forEach(BukkitTask::cancel); rotationTasks.clear();
        inRotation.clear();   // bots still online are picked up again by startRotationLoop
    }

    // ------------------------------------------------------------------ rotation

    private List<String> names() { return cfg.getStringList("rotation.names"); }

    private void startRotationLoop() {
        // A rotation bot already online (restored after a restart) gets its stay finished first.
        for (String n : names()) if (isOnline(n) && manager().getByName(n) != null && !claimedByLoop(n)) {
            claim(n);
            later(() -> leaveThenWait(n), minutes("rotation.online-minutes"));
            return;
        }
        later(this::joinNext, (long) (cfg.getDouble("rotation.first-delay-seconds", 15) * 20));
    }

    private final java.util.Set<String> inRotation = new java.util.HashSet<>();
    private boolean claimedByLoop(String n) { return inRotation.contains(n.toLowerCase(Locale.ROOT)); }
    private void claim(String n) { inRotation.add(n.toLowerCase(Locale.ROOT)); }

    private void joinNext() {
        List<String> free = new ArrayList<>();
        for (String n : names()) if (!isOnline(n) && !claimedByLoop(n) && !aliasOwnerOnline(n)) free.add(n);
        if (free.isEmpty()) { later(this::joinNext, 20L * 60 * 5); return; }   // everyone is on: look again in 5 minutes
        String n = free.get(ThreadLocalRandom.current().nextInt(free.size()));
        if (!spawn(n)) { later(this::joinNext, 20L * 60 * 5); return; }
        claim(n);
        later(() -> leaveThenWait(n), minutes("rotation.online-minutes"));
    }

    private void leaveThenWait(String n) {
        inRotation.remove(n.toLowerCase(Locale.ROOT));
        // An alt whose real player is online now stays: the alias rule owns it from here.
        if (manager().getByName(n) != null && !aliasOwnerOnline(n)) manager().delete(n, REASON);
        later(this::joinNext, minutes("rotation.offline-minutes"));
    }

    // ------------------------------------------------------------------ aliases

    /** real player (lower case) -> section or plain alias name */
    private Map<String, Object> aliases() {
        Map<String, Object> m = new HashMap<>();
        ConfigurationSection s = cfg.getConfigurationSection("aliases.players");
        if (s != null) for (String k : s.getKeys(false)) m.put(k.toLowerCase(Locale.ROOT), s.get(k));
        return m;
    }

    private String aliasOf(String player) {
        Object o = aliases().get(player.toLowerCase(Locale.ROOT));
        if (o instanceof ConfigurationSection cs) return cs.getString("alias");
        return o == null ? null : String.valueOf(o);
    }

    private boolean aliasOnJoin(String player) {
        Object o = aliases().get(player.toLowerCase(Locale.ROOT));
        return !(o instanceof ConfigurationSection cs) || cs.getBoolean("on-join", true);
    }

    /** True when n is someone's alt and that someone is online (or their alt's leave is pending). */
    private boolean aliasOwnerOnline(String n) {
        if (!cfg.getBoolean("aliases.enabled", false)) return false;
        for (String p : aliases().keySet()) {
            String a = aliasOf(p);
            if (a != null && a.equalsIgnoreCase(n) && (Bukkit.getPlayerExact(p) != null || aliasLeaveTasks.containsKey(p))) return true;
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // A bot's own join fires this too; wait a tick so the manager knows it, then skip bots.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || manager().getByUuid(p.getUniqueId()) != null) return;
            if (!cfg.getBoolean("aliases.enabled", false)) return;
            String alias = aliasOf(p.getName());
            if (alias == null) return;
            BukkitTask pending = aliasLeaveTasks.remove(p.getName().toLowerCase(Locale.ROOT));
            if (pending != null) pending.cancel();                     // came back before the alt left
            if (aliasOnJoin(p.getName()) && !isOnline(alias)) spawn(alias);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!cfg.getBoolean("aliases.enabled", false) || manager().getByUuid(p.getUniqueId()) != null) return;
        String alias = aliasOf(p.getName());
        if (alias == null) return;
        String key = p.getName().toLowerCase(Locale.ROOT);
        BukkitTask old = aliasLeaveTasks.remove(key);
        if (old != null) old.cancel();
        // A random time between min and max (the owner, 2026-11-13: 5-30); a plain number is a fixed time.
        long delay = cfg.isConfigurationSection("aliases.leave-after-minutes") ? minutes("aliases.leave-after-minutes")
                : (long) (cfg.getDouble("aliases.leave-after-minutes", 15) * 60 * 20);
        aliasLeaveTasks.put(key, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            aliasLeaveTasks.remove(key);
            if (Bukkit.getPlayerExact(p.getName()) == null && manager().getByName(alias) != null) manager().delete(alias, REASON);
        }, Math.max(1, delay)));
    }

    /** /ajoin [player]: brings that player's alt in now. */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return aliasCommand(sender, args);
    }

    public boolean aliasCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cpp.alias") && !sender.hasPermission("cpp.op")) { sender.sendMessage("§cYou may not do that."); return true; }
        String who = args.length > 0 ? args[0] : (sender instanceof Player p ? p.getName() : null);
        if (who == null) { sender.sendMessage("§cUsage: /ajoin <player>"); return true; }
        String alias = aliasOf(who);
        if (alias == null) { sender.sendMessage("§c" + who + " has no alias in auto-join.yml."); return true; }
        if (isOnline(alias)) { sender.sendMessage("§7" + alias + " is already online."); return true; }
        sender.sendMessage(spawn(alias) ? "§a" + alias + " joined." : "§c" + alias + " could not join (see the console).");
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private boolean isOnline(String name) { return Bukkit.getPlayerExact(name) != null || manager().getByName(name) != null; }

    private boolean spawn(String name) {
        Location l = location();
        if (l == null) { plugin.getLogger().warning("[auto-join] no spawn location: set location in auto-join.yml"); return false; }
        int r = manager().spawn(l, 1, null, name, true);
        if (r <= 0) plugin.getLogger().warning("[auto-join] could not spawn " + name + " (result " + r + ")");
        return r > 0;
    }

    /** "world, x, y, z[, yaw, pitch]"; empty = the first world's spawn. */
    private Location location() {
        String s = cfg.getString("location", "").trim();
        if (s.isEmpty()) return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getSpawnLocation();
        String[] a = s.split("\\s*,\\s*");
        World w = Bukkit.getWorld(a[0]);
        if (w == null || a.length < 4) return null;
        try {
            return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                    a.length > 4 ? Float.parseFloat(a[4]) : 0, a.length > 5 ? Float.parseFloat(a[5]) : 0);
        } catch (NumberFormatException ex) { return null; }
    }

    /** A random time in ticks from a "min: / max:" minutes section. */
    private long minutes(String path) {
        double min = cfg.getDouble(path + ".min", 10), max = Math.max(min, cfg.getDouble(path + ".max", min));
        double m = min + ThreadLocalRandom.current().nextDouble() * (max - min);
        return Math.max(20L, (long) (m * 60 * 20));
    }

    private void later(Runnable r, long ticks) {
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> { rotationTasks.remove(self[0]); r.run(); }, Math.max(1, ticks));
        rotationTasks.add(self[0]);
    }
}
