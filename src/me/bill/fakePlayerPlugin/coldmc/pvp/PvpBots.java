package me.bill.fakePlayerPlugin.coldmc.pvp;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;

/**
 * ColdMC PvP bots: the service that owns every {@link BotBrain}, keeps KitPvP populated
 * (auto-fill), records real players' fights ({@link CombatRecorder}) and holds the trained
 * model. Settings: {@code plugins/ColdPlayersPlus/pvp-bots.yml}.
 */
public final class PvpBots implements Listener {
    /** Set on every ColdPlayersPlus bot. KitPvP reads it to keep bots out of stats and rewards. */
    public static final String BOT_META = "cpp_bot";

    private final FakePlayerPlugin plugin;
    private volatile PvpSettings settings;
    private KitPvpHook kitpvp;
    private WorldGuardHook worldGuard;
    private static volatile PvpBots instance;
    private final CombatView view = new CombatView();
    private CombatRecorder recorder;
    private final Map<UUID, BotBrain> brains = new LinkedHashMap<>();
    /** Bots that should get a brain when they (re)appear: lower-case name -> [profile, autofill?]. */
    private final Map<String, String[]> pending = new LinkedHashMap<>();
    /** Names spawned by this service, with when - so a spawn in flight is counted against the caps. */
    private final Map<String, Long> recentSpawns = new LinkedHashMap<>();
    private volatile Mlp model;
    private volatile String modelStatus = "none loaded (heuristic brain)";
    private long modelStamp = -1;
    private long tick;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private long lastFlush, lastFill, lastModelCheck;
    private boolean staleSwept;
    /** False while no real player is anywhere the bots could fight (idle-when-empty): brains and auto-fill stand still. */
    private volatile boolean awake = true;

    public PvpBots(FakePlayerPlugin plugin) { this.plugin = plugin; }

    FakePlayerPlugin plugin() { return plugin; }
    PvpSettings settings() { return settings; }
    CombatView view() { return view; }
    long tick() { return tick; }
    Mlp model() { return model; }
    boolean awake() { return awake; }
    KitPvpHook kitpvp() { return settings.kitpvp && kitpvp != null && kitpvp.alive() ? kitpvp : null; }
    private FakePlayerManager manager() { return plugin.getFakePlayerManager(); }

    // ================================================================== lifecycle

    public void enable() {
        loadSettings();
        kitpvp = KitPvpHook.tryCreate();
        worldGuard = WorldGuardHook.tryCreate();
        instance = this;
        recorder = new CombatRecorder(this);
        recorder.start();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(recorder, plugin);
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::everySecond, 20L, 20L));
        loadState();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> loadModel(false, null));
        plugin.getLogger().info("[pvp] " + (settings.enabled ? "on" : "off") + ", KitPvP " + (kitpvp != null ? "found" : "not found")
                + ", collection " + (settings.collect ? "on" : "off") + ", " + settings.profiles.size() + " profiles.");
    }

    public void disable() {
        instance = null;
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        saveState();
        for (BotBrain b : brains.values()) detachQuiet(b);
        brains.clear();
        HandlerList.unregisterAll(this);
        if (recorder != null) { HandlerList.unregisterAll(recorder); recorder.flush(true); }
    }

    public void reload() {
        loadSettings();
        for (BotBrain b : brains.values()) b.profile = settings.profile(b.profile.id);
        lastModelCheck = 0;
    }

    private void loadSettings() {
        File f = new File(plugin.getDataFolder(), "pvp-bots.yml");
        if (!f.exists()) plugin.saveResource("pvp-bots.yml", false);
        settings = new PvpSettings(YamlConfiguration.loadConfiguration(f));
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent e) {
        if (e.getPlugin().getName().equals("KitPvP")) kitpvp = KitPvpHook.tryCreate();
        if (e.getPlugin().getName().equals("WorldGuard")) worldGuard = WorldGuardHook.tryCreate();
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent e) { if (e.getPlugin().getName().equals("KitPvP")) kitpvp = null; }

    // ================================================================== ticking

    private long nanosWindow, ticksWindow;
    private volatile double avgMicros;

    /** Average main-thread time of the PvP bots (brains + recorder sampling) per tick, over the last ~10 s. */
    double avgMicros() { return avgMicros; }

    private void tickAll() {
        long t0 = System.nanoTime();
        try { tickInner(); } finally {
            nanosWindow += System.nanoTime() - t0;
            if (++ticksWindow >= 200) { avgMicros = nanosWindow / 1000.0 / ticksWindow; nanosWindow = 0; ticksWindow = 0; }
        }
    }

    private void tickInner() {
        tick++;
        if (!settings.enabled && !(settings.collect && kitpvp() != null)) return;
        if (!awake) return; // nobody to fight or to record: no work at all
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        KitPvpHook kp = kitpvp();
        boolean sample = settings.collect && kp != null && recorder != null && tick % settings.sampleEvery == 0;
        if (!brains.isEmpty() || sample) {
            for (Player p : online) if (kp != null ? kp.inGame(p) : brains.containsKey(p.getUniqueId())) view.observe(p, tick);
        }
        if (settings.enabled) {
            for (BotBrain b : new ArrayList<>(brains.values())) {
                Player bp = b.fp.getPlayer();
                if (bp != null) view.observe(bp, tick);
                try {
                    b.tick(tick);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("[pvp] brain of " + b.fp.getName() + " failed: " + ex);
                    if (plugin.getConfig().getBoolean("debug", false)) ex.printStackTrace();
                }
            }
        }
        if (sample) sampleHumans(online, kp);
    }

    private void sampleHumans(Collection<? extends Player> online, KitPvpHook kp) {
        for (Player p : online) if (!isBot(p) && kp.inGame(p)) recorder.noteActivity(p, tick);
        for (Player p : online) {
            BotBrain own = settings.testRecordBots ? brains.get(p.getUniqueId()) : null;
            if ((isBot(p) && own == null) || !kp.hasKit(p)) continue;
            if (own == null && recorder.afk(p, tick)) continue; // AFK: nothing to learn
            Player opp = null;
            double best = 16 * 16;
            for (Player o : online) {
                if (o == p || o.getWorld() != p.getWorld() || !kp.hasKit(o)) continue;
                if (!settings.recordVsBots && isBot(o)) continue;
                if (recorder.afk(o, tick)) continue; // beating someone AFK teaches nothing
                if (!kp.sameArena(p, o) && !(kp.inDuel(p) && kp.inDuel(o))) continue;
                double d = o.getLocation().distanceSquared(p.getLocation());
                if (d < best) { best = d; opp = o; }
            }
            // stay with the one they are actually fighting, so one fight is not split between passers-by
            UUID focus = recorder.focus(p, tick);
            Player f = focus == null ? null : Bukkit.getPlayer(focus);
            if (f != null && f.getWorld() == p.getWorld() && kp.hasKit(f) && f.getLocation().distanceSquared(p.getLocation()) < 16 * 16
                    && (settings.recordVsBots || !isBot(f)) && !recorder.afk(f, tick)) opp = f;
            if (opp == null) continue;
            recorder.sample(p, opp, view.features(p, opp, enemiesNear(p, opp), tick, this::soupCount), tick, own == null ? null : own.intent());
        }
    }

    private void everySecond() {
        long now = System.currentTimeMillis();
        updateAwake();
        for (FakePlayer fp : manager().getActivePlayers()) {
            Player p = fp.getPlayer();
            if (p != null && !p.hasMetadata(BOT_META)) p.setMetadata(BOT_META, new FixedMetadataValue(plugin, true));
            if (p != null) tagNoKillExp(p);
            if (p != null && !brains.containsKey(fp.getUuid())) {
                String[] want = pending.get(fp.getName().toLowerCase(Locale.ROOT));
                if (want != null) attach(fp, want[0], "1".equals(want[1]));
            }
        }
        // two minutes after start, an auto-fill bot from before the restart that did not come back is forgotten
        if (!staleSwept && tick > 2400) {
            staleSwept = true;
            pending.entrySet().removeIf(e -> "1".equals(e.getValue()[1]) && manager().getByName(e.getKey()) == null && !recentSpawns.containsKey(e.getKey()));
        }
        if (recorder != null) {
            recorder.sweep(tick);
            if (now - lastFlush >= settings.flushSeconds * 1000L) {
                lastFlush = now;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> recorder.flush(false));
            }
        }
        if (settings.watchModelSeconds > 0 && now - lastModelCheck >= settings.watchModelSeconds * 1000L) {
            lastModelCheck = now;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> loadModel(false, null));
        }
        if (awake && settings.enabled && settings.autoFill && now - lastFill >= settings.checkSeconds * 1000L) {
            lastFill = now;
            autoFill();
        }
    }

    /**
     * Bots work only while a real player is in a KitPvP arena world (or a free-fight world), or a bot
     * was sent after someone with /cpp pvp fight. Otherwise every brain lets go of its keys and stops,
     * auto-fill neither spawns nor removes, and nothing is sampled (the owner, 2026-11-24).
     */
    private void updateAwake() {
        boolean want = !settings.idleWhenEmpty || realPlayerWhereBotsFight();
        if (!want) for (BotBrain b : brains.values()) if (b.forcedTarget != null) { want = true; break; }
        if (want == awake) return;
        awake = want;
        if (!want) for (BotBrain b : brains.values()) b.sleep(b.fp.getPlayer());
        plugin.getLogger().info("[pvp] " + (want ? "a player is in KitPvP: bots resume." : "no player in KitPvP: bots idle."));
    }

    private boolean realPlayerWhereBotsFight() {
        KitPvpHook kp = kitpvp();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isBot(p)) continue;
            String w = p.getWorld().getName();
            if ((kp != null && kp.isArenaWorld(w)) || settings.freeWorlds.contains(w)) return true;
        }
        return false;
    }

    /** MMOCore pays no kill EXP for an entity carrying mmocore:spawner_spawned (STRING) - javap of MMOCore 1.13.1. */
    private static final org.bukkit.NamespacedKey MMOCORE_NO_EXP = new org.bukkit.NamespacedKey("mmocore", "spawner_spawned");

    private void tagNoKillExp(Player bot) {
        var pdc = bot.getPersistentDataContainer();
        boolean has = pdc.has(MMOCORE_NO_EXP, org.bukkit.persistence.PersistentDataType.STRING);
        if (settings.noKillExp && !has) pdc.set(MMOCORE_NO_EXP, org.bukkit.persistence.PersistentDataType.STRING, "cpp_bot");
        else if (!settings.noKillExp && has) pdc.remove(MMOCORE_NO_EXP);
    }

    // ================================================================== brains

    public boolean isBot(Player p) { return p != null && manager().getByUuid(p.getUniqueId()) != null; }

    BotBrain brain(Player p) { return p == null ? null : brains.get(p.getUniqueId()); }

    Collection<BotBrain> brains() { return brains.values(); }

    /** Gives a bot a PvP brain. False if it already has one or the cap is reached. */
    boolean attach(FakePlayer fp, String profileId, boolean autoFill) {
        if (brains.containsKey(fp.getUuid())) return false;
        if (brains.size() >= settings.maxBots) return false;
        Player p = fp.getPlayer();
        if (p == null) return false;
        if (plugin.getPathfindingService() != null) plugin.getPathfindingService().cancel(fp.getUuid());
        manager().lockForNavigation(fp.getUuid()); // head-AI and idle look-around stay off while it fights
        BotBrain b = new BotBrain(this, fp, settings.profile(profileId), autoFill);
        brains.put(fp.getUuid(), b);
        pending.put(fp.getName().toLowerCase(Locale.ROOT), new String[]{b.profile.id, autoFill ? "1" : "0"});
        b.selectWeapon(p);
        return true;
    }

    /** Takes the brain away; the bot stays online. */
    boolean detach(FakePlayer fp) {
        pending.remove(fp.getName().toLowerCase(Locale.ROOT));
        BotBrain b = brains.remove(fp.getUuid());
        if (b == null) return false;
        detachQuiet(b);
        return true;
    }

    private void detachQuiet(BotBrain b) {
        Player p = b.fp.getPlayer();
        b.release(p);
        manager().unlockNavigation(b.fp.getUuid());
    }

    /** Everyone this bot could fight right now (before range and line-of-sight checks). */
    List<Player> candidates(Player bot) {
        List<Player> out = new ArrayList<>();
        KitPvpHook kp = kitpvp();
        if (kp != null && kp.inGame(bot)) {
            for (Player p : kp.arenaPlayers(kp.arenaId(bot))) if (p != bot && mayTarget(bot, p)) out.add(p);
            return out;
        }
        if (!settings.freeWorlds.contains(bot.getWorld().getName())) return out;
        for (Player p : bot.getWorld().getPlayers()) if (p != bot && mayTarget(bot, p)) out.add(p);
        return out;
    }

    /**
     * No fighting here: WorldGuard denies pvp, or it is inside the bot spawn zone of the arena.
     * Checked for the target (never pick, chase or hit them) and for the bot (never swing from it).
     */
    boolean noFightAt(Player bot, Location l) {
        if (settings.respectWorldGuard && worldGuard != null && !worldGuard.pvpAllowed(l)) return true;
        KitPvpHook kp = kitpvp();
        if (kp != null && settings.spawnSafeRadius > 0 && kp.inGame(bot)) return kp.nearSpawn(kp.arenaId(bot), l, settings.spawnSafeRadius);
        return false;
    }

    /** Right-clicking a bot does nothing special in KitPvP arena worlds and the configured ones (the owner, 2026-11-23). */
    public static boolean rightClickBlocked(org.bukkit.World w) {
        PvpBots s = instance;
        if (s == null || w == null) return false;
        String n = w.getName().toLowerCase(Locale.ROOT);
        for (String b : s.settings.rightClickBlocked) if (!b.isBlank() && n.startsWith(b.toLowerCase(Locale.ROOT))) return true;
        KitPvpHook kp = s.kitpvp();
        return kp != null && kp.isArenaWorld(w.getName());
    }

    boolean mayTarget(Player bot, Player p) {
        if (p == null || bot == null) return false;
        return isBot(p) ? settings.targetBots : settings.targetPlayers;
    }

    int enemiesNear(Player me, Player opp) {
        int n = 0;
        Location l = me.getLocation();
        KitPvpHook kp = kitpvp();
        for (Player p : me.getWorld().getPlayers()) {
            if (p == me || p == opp || p.getLocation().distanceSquared(l) > 100) continue;
            if (kp != null && !kp.hasKit(p)) continue;
            n++;
        }
        return n;
    }

    boolean isSoup(ItemStack it) {
        if (it == null) return false;
        KitPvpHook kp = kitpvp();
        return kp != null ? kp.isSoup(it) : it.getType() == Material.MUSHROOM_STEW;
    }

    int soupCount(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (isSoup(it)) n += it.getAmount();
        return n;
    }

    void tryJoin(Player bot) {
        KitPvpHook kp = kitpvp();
        if (kp == null) return;
        String arena = kp.resolveArena(settings.arena);
        if (arena != null) kp.join(bot, arena);
    }

    void pickKit(Player bot) {
        KitPvpHook kp = kitpvp();
        if (kp == null) return;
        List<String> kits = kp.pickable(settings.kits);
        if (kits.isEmpty()) return;
        kp.equip(bot, kits.get(ThreadLocalRandom.current().nextInt(kits.size())), settings.kitTier);
    }

    Location wanderCentre(Player bot) {
        KitPvpHook kp = kitpvp();
        if (kp != null && kp.inGame(bot)) {
            Location s = kp.arenaSpawn(kp.arenaId(bot));
            return s != null && s.getWorld() == bot.getWorld() ? s : bot.getLocation();
        }
        return settings.freeWorlds.contains(bot.getWorld().getName()) ? bot.getLocation() : null;
    }

    /** Inner radius for roaming around the wander centre: outside the bot spawn zone. */
    double wanderInner(Player bot) {
        KitPvpHook kp = kitpvp();
        return kp != null && kp.inGame(bot) ? settings.spawnSafeRadius + 2 : 4;
    }

    static Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof Tameable t && t.getOwner() instanceof Player p) return p;
        return null;
    }

    private Player botCause(Entity e) {
        if (e instanceof Player p) return isBot(p) ? p : null;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Player p) return isBot(p) ? p : null;
        return null;
    }

    /** A bot never sets blocks alight (combat.start-fires: false). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(org.bukkit.event.block.BlockIgniteEvent e) {
        if (settings.noArenaFire && arenaWorld(e.getBlock().getWorld())
                && (e.getCause() == org.bukkit.event.block.BlockIgniteEvent.IgniteCause.LAVA
                    || e.getCause() == org.bukkit.event.block.BlockIgniteEvent.IgniteCause.SPREAD)) { e.setCancelled(true); return; }
        if (settings.startFires) return;
        if ((e.getPlayer() != null && isBot(e.getPlayer())) || botCause(e.getIgnitingEntity()) != null) e.setCancelled(true);
    }

    private boolean arenaWorld(World w) { KitPvpHook kp = kitpvp(); return kp != null && w != null && kp.isArenaWorld(w.getName()); }

    /** Fire burns no blocks in a KitPvP arena world (combat.no-arena-fire). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(org.bukkit.event.block.BlockBurnEvent e) {
        if (settings.noArenaFire && arenaWorld(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFireSpread(org.bukkit.event.block.BlockSpreadEvent e) {
        if (settings.noArenaFire && e.getSource().getType() == Material.FIRE && arenaWorld(e.getBlock().getWorld())) e.setCancelled(true);
    }

    /** A bot's fireball or other explosion makes no fire. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPrime(org.bukkit.event.entity.ExplosionPrimeEvent e) {
        if (!settings.startFires && botCause(e.getEntity()) != null) e.setFire(false);
    }

    /** Burning arrows from a bot light nothing (they can still set the player they hit on fire). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombustBlock(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        if (!settings.startFires && botCause(e.getEntity()) != null && e.getTo() == Material.FIRE) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        BotBrain b = brains.get(victim.getUniqueId());
        if (b != null) b.hurtBy(attackerOf(e.getDamager()), tick);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            FakePlayer fp = manager().getByUuid(p.getUniqueId());
            if (fp == null || !p.isOnline()) return;
            p.setMetadata(BOT_META, new FixedMetadataValue(plugin, true));
            tagNoKillExp(p);
            String[] want = pending.get(fp.getName().toLowerCase(Locale.ROOT));
            if (want != null) attach(fp, want[0], "1".equals(want[1]));
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        view.forget(id);
        BotBrain b = brains.remove(id);
        if (b == null) return;
        manager().unlockNavigation(id);
        // an assigned bot keeps its place and gets its brain back when it is spawned again;
        // an auto-fill bot is simply replaced by auto-fill
        if (b.autoFill || !settings.reattach) pending.remove(b.fp.getName().toLowerCase(Locale.ROOT));
    }

    // ================================================================== auto-fill

    private void autoFill() {
        KitPvpHook kp = kitpvp();
        if (kp == null) return;
        String arena = kp.resolveArena(settings.arena);
        if (arena == null) return;
        int real = 0;
        for (Player p : kp.arenaPlayers(arena)) if (!isBot(p)) real++;
        List<BotBrain> filled = new ArrayList<>();
        int assigned = 0;
        for (BotBrain b : brains.values()) if (b.autoFill) filled.add(b); else assigned++;
        int waiting = pendingAutoFill();
        int want = Math.max(settings.minBots, settings.fillTo - real);
        want = Math.max(0, Math.min(want, settings.maxBots - assigned));
        if (filled.size() + waiting < want) {
            spawnBots(1, pickFillProfile(), true, kp.arenaSpawn(arena));
        } else if (filled.size() > want) {
            for (BotBrain b : filled) {
                Player p = b.fp.getPlayer();
                if (p != null && kp.inCombat(p)) continue; // never pull a bot out of a fight
                pending.remove(b.fp.getName().toLowerCase(Locale.ROOT));
                manager().delete(b.fp.getName(), "cpp_pvp_autofill");
                break;
            }
        }
    }

    private String pickFillProfile() {
        List<String> l = settings.fillProfiles;
        return l.get(ThreadLocalRandom.current().nextInt(l.size()));
    }

    /** Spawns bots that get a brain as soon as they are online. Returns how many spawns were started. */
    int spawnBots(int count, String profile, boolean autoFill, Location at) {
        Location loc = at;
        if (!autoFill || settings.spawnLocation.isBlank()) { if (loc == null) return 0; }
        else loc = parseLocation(settings.spawnLocation, at);
        if (loc == null) return 0;
        int started = 0;
        for (int i = 0; i < count && brains.size() + pendingSpawns() < settings.maxBots; i++) {
            String name = freeName();
            if (name == null) break;
            String key = name.toLowerCase(Locale.ROOT);
            pending.put(key, new String[]{settings.profile(profile).id, autoFill ? "1" : "0"});
            recentSpawns.put(key, System.currentTimeMillis());
            if (manager().spawn(loc, 1, null, name, true) > 0) started++;
            else { pending.remove(key); recentSpawns.remove(key); }
        }
        return started;
    }

    /** Spawns started in the last 30 s whose bot has not got its brain yet. */
    private int pendingSpawns() {
        long now = System.currentTimeMillis();
        recentSpawns.values().removeIf(t -> now - t > 30_000);
        int n = 0;
        for (String name : recentSpawns.keySet()) {
            FakePlayer fp = manager().getByName(name);
            if (fp == null || !brains.containsKey(fp.getUuid())) n++;
        }
        return n;
    }

    private int pendingAutoFill() {
        int n = 0;
        pendingSpawns();
        for (String name : recentSpawns.keySet()) {
            String[] v = pending.get(name);
            FakePlayer fp = manager().getByName(name);
            if (v != null && "1".equals(v[1]) && (fp == null || !brains.containsKey(fp.getUuid()))) n++;
        }
        return n;
    }

    private String freeName() {
        List<String> pool = new ArrayList<>(settings.names);
        java.util.Collections.shuffle(pool);
        for (String n : pool) if (Bukkit.getPlayerExact(n) == null && manager().getByName(n) == null && !pending.containsKey(n.toLowerCase(Locale.ROOT))) return n;
        for (int i = 0; i < 50; i++) {
            String n = "Bot" + (1000 + ThreadLocalRandom.current().nextInt(9000));
            if (Bukkit.getPlayerExact(n) == null && manager().getByName(n) == null && !pending.containsKey(n.toLowerCase(Locale.ROOT))) return n;
        }
        return null;
    }

    static Location parseLocation(String s, Location fallback) {
        if (s == null || s.isBlank()) return fallback;
        String[] a = s.split("\\s*,\\s*");
        World w = a.length > 0 ? Bukkit.getWorld(a[0]) : null;
        if (w == null || a.length < 4) return fallback;
        try {
            return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                    a.length > 4 ? Float.parseFloat(a[4]) : 0, a.length > 5 ? Float.parseFloat(a[5]) : 0);
        } catch (NumberFormatException e) { return fallback; }
    }

    // ================================================================== model

    File modelFile() { return new File(plugin.getDataFolder(), settings.learningFolder + "/" + settings.modelFile); }

    String modelStatus() { return modelStatus; }

    /**
     * Reads the model off the main thread, then swaps it in on the main thread. The swap is one
     * reference: a brain reads the model once per decision, so a fight in progress simply carries on
     * with the next decision made by the new model. A file that fails validation is refused and the
     * current model stays.
     */
    void loadModel(boolean force, org.bukkit.command.CommandSender report) {
        File f = modelFile();
        if (!f.exists()) {
            if (model != null || force) Bukkit.getScheduler().runTask(plugin, () -> { model = null; modelStatus = "none loaded (heuristic brain)"; modelStamp = -1; tell(report, "§7No model at " + f.getPath() + ": bots use the heuristic brain."); });
            return;
        }
        long stamp = f.lastModified() ^ f.length();
        if (!force && stamp == modelStamp) return;
        try {
            Mlp m = Mlp.load(Path.of(f.getPath()));
            String info = "loaded " + f.getName() + " (" + m.meta.getOrDefault("samples", "?") + " samples from " + m.meta.getOrDefault("players", "?")
                    + " players, trained " + m.meta.getOrDefault("trained", "?") + ")";
            Bukkit.getScheduler().runTask(plugin, () -> {
                model = m;
                modelStamp = stamp;
                modelStatus = info;
                plugin.getLogger().info("[pvp] model " + info);
                tell(report, "§a" + info);
            });
        } catch (Exception e) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                modelStamp = stamp; // do not retry the same bad file every minute
                plugin.getLogger().warning("[pvp] refused model " + f.getName() + ": " + e.getMessage() + " - keeping " + (model != null ? "the current model" : "the heuristic brain"));
                tell(report, "§cRefused " + f.getName() + ": " + e.getMessage());
            });
        }
    }

    private static void tell(org.bukkit.command.CommandSender s, String msg) { if (s != null) s.sendMessage(msg); }

    CombatRecorder recorder() { return recorder; }

    // ================================================================== state

    private File stateFile() { return new File(plugin.getDataFolder(), "pvp-state.yml"); }

    private void loadState() {
        File f = stateFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        var sec = y.getConfigurationSection("bots");
        if (sec == null) return;
        for (String name : sec.getKeys(false))
            pending.put(name.toLowerCase(Locale.ROOT), new String[]{sec.getString(name + ".profile", "default"), sec.getBoolean(name + ".auto-fill") ? "1" : "0"});
    }

    void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Bots that carry a PvP brain; they get it back when they are next online. Written by ColdPlayersPlus."));
        Set<String> done = new HashSet<>();
        for (BotBrain b : brains.values()) {
            String n = b.fp.getName();
            y.set("bots." + n + ".profile", b.profile.id);
            y.set("bots." + n + ".auto-fill", b.autoFill);
            done.add(n.toLowerCase(Locale.ROOT));
        }
        for (Map.Entry<String, String[]> e : pending.entrySet()) {
            if (done.contains(e.getKey())) continue;
            y.set("bots." + e.getKey() + ".profile", e.getValue()[0]);
            y.set("bots." + e.getKey() + ".auto-fill", "1".equals(e.getValue()[1]));
        }
        try { y.save(stateFile()); } catch (Exception e) { plugin.getLogger().warning("[pvp] could not save pvp-state.yml: " + e.getMessage()); }
    }

    Map<String, String[]> pending() { return pending; }
}
