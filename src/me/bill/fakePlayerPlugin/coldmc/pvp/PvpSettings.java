package me.bill.fakePlayerPlugin.coldmc.pvp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** pvp-bots.yml, read once per load. Immutable, so a reload swaps the whole object. */
final class PvpSettings {
    final boolean enabled;
    final boolean idleWhenEmpty;
    final int maxBots;
    final boolean kitpvp;
    final String arena;
    final List<String> kits;
    final String kitTier;
    final int pickMinTicks, pickMaxTicks;
    final boolean autoFill;
    final int minBots, fillTo, checkSeconds;
    final List<String> fillProfiles;
    final String spawnLocation;
    final List<String> names;
    final boolean targetPlayers, targetBots;
    final double targetRange;
    final List<String> freeWorlds;
    final int maxSafeDrop;
    final boolean noKillExp, respectWorldGuard, startFires, noArenaFire;
    final double spawnSafeRadius;
    final List<String> rightClickBlocked;
    final boolean reattach;
    final boolean collect, recordVsBots, testRecordBots;
    final int sampleEvery;
    final int afkTicks;
    final double minDealt, minTaken;
    final String learningFolder;
    final int flushSeconds, fileMinutes, maxQueue, maxDiskMb, retentionDays;
    final String modelFile;
    final int watchModelSeconds;
    final Map<String, Profile> profiles = new LinkedHashMap<>();

    PvpSettings(YamlConfiguration c) {
        enabled = c.getBoolean("enabled", true);
        idleWhenEmpty = c.getBoolean("idle-when-empty", true);
        maxBots = Math.max(0, c.getInt("max-bots", 8));
        kitpvp = c.getBoolean("kitpvp.enabled", true);
        arena = c.getString("kitpvp.arena", "");
        kits = c.getStringList("kitpvp.kits");
        kitTier = c.getString("kitpvp.kit-tier", "1");
        pickMinTicks = Math.max(0, (int) (c.getDouble("kitpvp.pick-kit-seconds.min", 2) * 20));
        pickMaxTicks = Math.max(pickMinTicks, (int) (c.getDouble("kitpvp.pick-kit-seconds.max", 6) * 20));
        autoFill = c.getBoolean("kitpvp.auto-fill.enabled", false);
        minBots = Math.max(0, c.getInt("kitpvp.auto-fill.min-bots", 2));
        fillTo = Math.max(0, c.getInt("kitpvp.auto-fill.fill-to", 6));
        checkSeconds = Math.max(5, c.getInt("kitpvp.auto-fill.check-seconds", 20));
        List<String> fp = c.getStringList("kitpvp.auto-fill.profiles");
        fillProfiles = fp.isEmpty() ? List.of("casual") : fp;
        spawnLocation = c.getString("kitpvp.auto-fill.spawn-location", "");
        names = new ArrayList<>(c.getStringList("names"));
        names.removeIf(n -> n == null || !n.matches("[A-Za-z0-9_]{3,16}"));
        targetPlayers = c.getBoolean("combat.bots-target-players", true);
        targetBots = c.getBoolean("combat.bots-target-bots", true);
        targetRange = Math.max(4, Math.min(64, c.getDouble("combat.target-range", 24)));
        freeWorlds = new ArrayList<>(c.getStringList("combat.free-worlds"));
        maxSafeDrop = Math.max(1, Math.min(32, c.getInt("combat.max-safe-drop", 4)));
        noKillExp = c.getBoolean("combat.no-kill-exp", true);
        respectWorldGuard = c.getBoolean("combat.respect-worldguard", true);
        startFires = c.getBoolean("combat.start-fires", false);
        noArenaFire = c.getBoolean("combat.no-arena-fire", true);
        spawnSafeRadius = Math.max(0, c.getDouble("combat.spawn-safe-radius", 10));
        rightClickBlocked = c.isList("right-click.blocked-worlds") ? c.getStringList("right-click.blocked-worlds") : List.of("instance", "world_kitbattle");
        reattach = c.getBoolean("respawn.reattach-after-death", true);
        collect = c.getBoolean("learning.collect", true);
        recordVsBots = c.getBoolean("learning.record-vs-bots", false);
        testRecordBots = c.getBoolean("learning.test-record-bots", false);
        sampleEvery = Math.max(1, Math.min(20, c.getInt("learning.sample-every-ticks", 2)));
        afkTicks = (int) Math.max(20, c.getDouble("learning.afk-seconds", 5) * 20);
        minDealt = Math.max(0, c.getDouble("learning.min-damage-dealt", 1));
        minTaken = Math.max(0, c.getDouble("learning.min-damage-taken", 1));
        learningFolder = c.getString("learning.folder", "pvp-learning").replace("..", "");
        flushSeconds = Math.max(5, c.getInt("learning.flush-seconds", 30));
        fileMinutes = Math.max(5, c.getInt("learning.file-minutes", 60));
        maxQueue = Math.max(1000, c.getInt("learning.max-queue", 20000));
        maxDiskMb = Math.max(1, c.getInt("learning.max-disk-mb", 1024));
        retentionDays = Math.max(0, c.getInt("learning.retention-days", 0));
        modelFile = c.getString("learning.model-file", "combat-model.txt").replace("..", "");
        watchModelSeconds = Math.max(0, c.getInt("learning.watch-model-seconds", 60));

        ConfigurationSection ps = c.getConfigurationSection("profiles");
        Profile def = Profile.of("default", ps != null && ps.isConfigurationSection("default") ? ps.getConfigurationSection("default") : new YamlConfiguration(), null);
        profiles.put("default", def);
        if (ps != null) for (String k : ps.getKeys(false)) {
            if (k.equalsIgnoreCase("default") || !ps.isConfigurationSection(k)) continue;
            profiles.put(k.toLowerCase(), Profile.of(k.toLowerCase(), ps.getConfigurationSection(k), def));
        }
    }

    Profile profile(String id) {
        Profile p = id == null ? null : profiles.get(id.toLowerCase());
        return p != null ? p : profiles.get("default");
    }

    int pickKitTicks() { return Profile.between(pickMinTicks, pickMaxTicks); }
}
