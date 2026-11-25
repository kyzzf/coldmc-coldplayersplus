package me.bill.fakePlayerPlugin.coldmc.pvp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import se.coldmc.kitpvp.Arena;
import se.coldmc.kitpvp.Kit;
import se.coldmc.kitpvp.KitPvP;
import se.coldmc.kitpvp.Session;

/**
 * Everything ColdPlayersPlus asks of KitPvP, in one class. Only this class names KitPvP's types,
 * and it is only loaded when KitPvP is enabled, so ColdPlayersPlus still runs without KitPvP.
 * Bots go through KitPvP's own join, equip and session code - the same paths a player's command
 * or menu click takes - so every arena rule applies to them.
 */
final class KitPvpHook {
    private final KitPvP kp;

    private KitPvpHook(KitPvP kp) { this.kp = kp; }

    static KitPvpHook tryCreate() {
        Plugin p = Bukkit.getPluginManager().getPlugin("KitPvP");
        if (p == null || !p.isEnabled()) return null;
        try {
            return p instanceof KitPvP k && k.game != null ? new KitPvpHook(k) : null;
        } catch (Throwable t) { // an older KitPvP without these fields
            return null;
        }
    }

    boolean alive() { return kp.isEnabled() && kp.game != null; }

    private Session s(Player p) { return kp.game.session(p); }

    boolean inGame(Player p) { return s(p) != null; }
    boolean hasKit(Player p) { Session s = s(p); return s != null && s.kit != null; }
    String kitId(Player p) { Session s = s(p); return s == null ? null : s.kitId(); }
    String arenaId(Player p) { Session s = s(p); return s == null || s.arena == null ? null : s.arena.id; }
    boolean inDuel(Player p) { Session s = s(p); return s != null && s.duel != null; }
    boolean inCombat(Player p) { Session s = s(p); return s != null && s.inCombat(); }

    /** Someone a fighter could hit right now: inside, armed, not spawn-protected, not in a safe zone, not in a duel. */
    boolean fightable(Player p) {
        Session s = s(p);
        if (s == null || s.kit == null || s.duel != null || s.spawnProtected()) return false;
        return s.arena == null || !s.arena.inSafeZone(p.getLocation());
    }

    boolean sameArena(Player a, Player b) {
        Session x = s(a), y = s(b);
        return x != null && y != null && x.arena == y.arena && x.duel == null && y.duel == null;
    }

    boolean isArenaWorld(String world) { return kp.game.isArenaWorld(world); }

    // ---------------------------------------------------------------- duels (queue fill, the owner 2026-11-25)

    /** Everyone waiting in a duel queue: uuid -> queue id. */
    java.util.Map<java.util.UUID, String> queued() {
        java.util.Map<java.util.UUID, String> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.LinkedHashSet<java.util.UUID>> e : kp.game.queues.entrySet())
            for (java.util.UUID u : e.getValue()) out.put(u, e.getKey());
        return out;
    }

    int queueSize(String queue) { return kp.game.queueSize(queue); }

    boolean queueHasFreeArena(String queue) { return kp.game.freeArena(queue) != null; }

    boolean inQueue(Player p) { return kp.game.queueOf(p) != null; }

    /** Starts a duel between a and b on a free arena of the queue, through KitPvP's own startDuel. */
    boolean startDuel(Player a, Player b, String queue) {
        Session x = s(a), y = s(b);
        if (x == null || y == null || x.duel != null || y.duel != null || x.inCombat() || y.inCombat()) return false;
        Arena free = kp.game.freeArena(queue);
        if (free == null) return false;
        kp.game.startDuel(x, y, free);
        return x.duel != null && x.duel == y.duel;
    }

    /** The other side of p's duel, or null. */
    Player duelOpponent(Player p) {
        Session s = s(p);
        if (s == null || s.duel == null || s.duel.over) return null;
        Session o = s.duel.other(s);
        return o == null ? null : o.player;
    }

    /** True once p's duel countdown (or kit-choice window) is over and the fight is on. */
    boolean duelStarted(Player p) {
        Session s = s(p);
        return s != null && s.duel != null && !s.duel.over && s.duel.started();
    }

    // KitPvP's own isSoup() runs Material.matchMaterial (a regex) on every call; the brain asks per
    // inventory slot per decision, which cost ~13 ms a tick with 3 bots (jstack, dev 2026-11-23).
    private org.bukkit.Material soup;
    private long soupReadAt;

    boolean isSoup(ItemStack it) {
        if (it == null) return false;
        long now = System.currentTimeMillis();
        if (now - soupReadAt > 1000) {
            soupReadAt = now;
            soup = org.bukkit.Material.matchMaterial(kp.getConfig().getString("Soup.Item", "MUSHROOM_STEW"));
        }
        return soup != null && it.getType() == soup;
    }

    /** Players inside the same FFA arena (any state). */
    List<Player> arenaPlayers(String arenaId) {
        List<Player> out = new ArrayList<>();
        for (Session s : kp.game.sessions.values())
            if (s.arena != null && s.arena.id.equalsIgnoreCase(arenaId) && s.duel == null && s.player.isOnline()) out.add(s.player);
        return out;
    }

    String resolveArena(String wanted) {
        String id = wanted == null || wanted.isBlank() ? kp.getConfig().getString("Arenas.Default", "") : wanted;
        Arena a = kp.game.arena(id);
        return a != null && a.enabled && a.type == Arena.Type.FFA ? a.id : null;
    }

    /** Within r blocks of any spawn of the arena. */
    boolean nearSpawn(String arenaId, Location l, double r) {
        Arena a = kp.game.arena(arenaId);
        if (a == null || r <= 0) return false;
        for (String s : a.spawns) {
            Location sp = Arena.toLocation(s);
            if (sp != null && sp.getWorld() == l.getWorld() && sp.distanceSquared(l) <= r * r) return true;
        }
        return false;
    }

    Location arenaSpawn(String arenaId) {
        Arena a = kp.game.arena(arenaId);
        return a == null ? null : a.spawn(0);
    }

    /** KitPvP's real join: snapshot, strip, teleport, lobby items. */
    boolean join(Player p, String arenaId) {
        Arena a = kp.game.arena(arenaId);
        return a != null && kp.game.join(p, a);
    }

    void leave(Player p) { if (inGame(p)) kp.game.leave(p, true, true); }

    /** Kit ids a bot may pick: the configured list, or every open kit (enabled, visible, no permission). */
    List<String> pickable(List<String> configured) {
        List<String> out = new ArrayList<>();
        if (configured != null && !configured.isEmpty()) {
            for (String id : configured) { Kit k = kp.game.kit(id); if (k != null && k.enabled && !k.hidden && !k.tiers.isEmpty()) out.add(k.id); }
            return out;
        }
        for (Kit k : kp.game.kits.values()) if (k.enabled && !k.hidden && k.permission.isEmpty() && !k.tiers.isEmpty()) out.add(k.id);
        return out;
    }

    /**
     * Puts a kit on a bot through KitPvP's own equip (items, soup fill, effects, equip scripts).
     * A bot never buys: the tier comes from pvp-bots.yml, not from the bot's wallet.
     */
    boolean equip(Player p, String kitId, String tierSpec) {
        Session s = s(p);
        Kit k = kp.game.kit(kitId);
        if (s == null || k == null || k.tiers.isEmpty() || s.duel != null) return false;
        int tier;
        String spec = tierSpec == null ? "1" : tierSpec.trim().toLowerCase(Locale.ROOT);
        if (spec.equals("max")) tier = k.maxTier();
        else if (spec.equals("random")) {
            List<Integer> keys = new ArrayList<>(k.tiers.keySet());
            tier = keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
        } else {
            try { tier = Integer.parseInt(spec); } catch (NumberFormatException e) { tier = k.tiers.firstKey(); }
            if (!k.tiers.containsKey(tier)) tier = k.tiers.floorKey(tier) != null ? k.tiers.floorKey(tier) : k.tiers.firstKey();
        }
        kp.game.equip(s, k, tier, true);
        return true;
    }
}
