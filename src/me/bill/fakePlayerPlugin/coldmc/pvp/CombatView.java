package me.bill.fakePlayerPlugin.coldmc.pvp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Turns a fight situation into the numbers of {@link PvpSchema#FEATURES}. The recorder (real
 * players) and the brain (bots) both use this one class, so what the model was trained on and
 * what it sees in game can never drift apart.
 *
 * <p>Also keeps the little per-player history a single snapshot cannot give: last tick's position
 * (for speeds) and the tick of the last swing (for attack readiness). Main thread only.
 */
final class CombatView {
    private static final class Track {
        double x, y, z, px, py, pz;
        long tick = Long.MIN_VALUE, prevTick = Long.MIN_VALUE;
        long lastSwing = -1000;
    }

    private final Map<UUID, Track> tracks = new ConcurrentHashMap<>();

    /** Call once per tick for every player who may be observed or may fight. */
    void observe(Player p, long tick) {
        Track t = tracks.computeIfAbsent(p.getUniqueId(), k -> new Track());
        if (t.tick == tick) return;
        Location l = p.getLocation();
        t.px = t.tick == Long.MIN_VALUE ? l.getX() : t.x;
        t.py = t.tick == Long.MIN_VALUE ? l.getY() : t.y;
        t.pz = t.tick == Long.MIN_VALUE ? l.getZ() : t.z;
        t.prevTick = t.tick == Long.MIN_VALUE ? tick - 1 : t.tick;
        t.x = l.getX(); t.y = l.getY(); t.z = l.getZ();
        t.tick = tick;
    }

    void swung(UUID id, long tick) { tracks.computeIfAbsent(id, k -> new Track()).lastSwing = tick; }

    long lastSwing(UUID id) { Track t = tracks.get(id); return t == null ? -1000 : t.lastSwing; }

    void forget(UUID id) { tracks.remove(id); }

    /** Horizontal velocity in blocks per tick (x, z). */
    double[] velocity(UUID id) {
        Track t = tracks.get(id);
        if (t == null || t.prevTick == Long.MIN_VALUE) return new double[]{0, 0};
        double dt = Math.max(1, t.tick - t.prevTick);
        return new double[]{(t.x - t.px) / dt, (t.z - t.pz) / dt};
    }

    float[] features(Player me, Player opp, int enemiesNear, long tick, SoupCheck soup) {
        float[] f = new float[PvpSchema.N];
        Location a = me.getLocation(), b = opp.getLocation();
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        f[0] = clamp(dist / 4.0, 0, 4);
        f[1] = clamp((b.getY() - a.getY()) / 3.0, -2, 2);
        double toOpp = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = Math.toRadians(wrap(toOpp - a.getYaw()));
        f[2] = (float) Math.sin(rel);
        f[3] = (float) Math.cos(rel);
        double toMe = Math.toDegrees(Math.atan2(dx, -dz));
        f[4] = (float) Math.cos(Math.toRadians(wrap(toMe - b.getYaw())));
        f[5] = (float) hp(me);
        f[6] = (float) hp(opp);
        f[7] = clamp(attr(me, Attribute.ARMOR) / 20.0, 0, 1.5);
        f[8] = clamp(attr(opp, Attribute.ARMOR) / 20.0, 0, 1.5);
        f[9] = clamp(weaponDamage(me.getInventory().getItemInMainHand()) / 10.0, 0, 2);
        f[10] = clamp(weaponDamage(opp.getInventory().getItemInMainHand()) / 10.0, 0, 2);
        f[11] = clamp((tick - lastSwing(me.getUniqueId())) / attackIntervalTicks(me), 0, 1.5);
        f[12] = clamp(me.getNoDamageTicks() / 20.0, 0, 1);
        f[13] = clamp(opp.getNoDamageTicks() / 20.0, 0, 1);
        double[] va = velocity(me.getUniqueId()), vb = velocity(opp.getUniqueId());
        double ux = dist > 1e-4 ? dx / dist : 0, uz = dist > 1e-4 ? dz / dist : 0;
        double closing = (va[0] - vb[0]) * ux + (va[1] - vb[1]) * uz;
        f[14] = clamp(closing * 4, -2, 2);
        double lateral = -vb[0] * uz + vb[1] * ux;
        f[15] = clamp(lateral * 4, -2, 2);
        f[16] = me.isOnGround() ? 1 : 0;
        f[17] = opp.isOnGround() ? 1 : 0;
        f[18] = clamp(soup.count(me) / 8.0, 0, 1);
        f[19] = clamp(enemiesNear / 4.0, 0, 1);
        f[20] = clamp(me.getFallDistance() / 2.0, 0, 1);
        f[21] = opp.isBlocking() ? 1 : 0;
        f[22] = isRanged(me.getInventory().getItemInMainHand()) ? 1 : 0;
        return f;
    }

    interface SoupCheck { int count(Player p); }

    // ------------------------------------------------------------------ helpers shared with the brain

    static double hp(Player p) {
        double max = attr(p, Attribute.MAX_HEALTH);
        return max <= 0 ? 0 : Math.max(0, Math.min(1, p.getHealth() / max));
    }

    static double attr(Player p, Attribute a) {
        AttributeInstance i = p.getAttribute(a);
        return i == null ? 0 : i.getValue();
    }

    /** Ticks between two full-strength swings with what is held (1.9 cooldown; tiny under 1.8 attack speed). */
    static double attackIntervalTicks(Player p) {
        double speed = attr(p, Attribute.ATTACK_SPEED);
        return speed <= 0 ? 10 : Math.max(1, 20.0 / speed);
    }

    /** A rough melee damage of an item: vanilla base damage plus Sharpness. Only used as a feature and to pick the weapon slot. */
    static double weaponDamage(ItemStack it) {
        if (it == null || it.getType().isAir()) return 1;
        String n = it.getType().name();
        double base;
        if (n.endsWith("_SWORD")) base = tierDamage(n, 4, 4, 5, 6, 7, 8);
        else if (n.endsWith("_AXE")) base = tierDamage(n, 7, 7, 9, 9, 9, 10);
        else if (n.equals("MACE")) base = 6;
        else if (n.equals("TRIDENT")) base = 9;
        else if (n.endsWith("_SHOVEL")) base = tierDamage(n, 2.5, 2.5, 3.5, 4.5, 5.5, 6.5);
        else if (n.endsWith("_PICKAXE")) base = tierDamage(n, 2, 2, 3, 4, 5, 6);
        else if (n.endsWith("_HOE")) base = 1;
        else base = 1;
        int sharp = it.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharp > 0) base += 0.5 * sharp + 0.5;
        return base;
    }

    private static double tierDamage(String n, double wood, double gold, double stone, double iron, double diamond, double netherite) {
        if (n.startsWith("WOODEN")) return wood;
        if (n.startsWith("GOLDEN")) return gold;
        if (n.startsWith("STONE")) return stone;
        if (n.startsWith("IRON")) return iron;
        if (n.startsWith("DIAMOND")) return diamond;
        if (n.startsWith("NETHERITE")) return netherite;
        return stone;
    }

    static boolean isRanged(ItemStack it) {
        if (it == null) return false;
        Material m = it.getType();
        return m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT;
    }

    static double wrap(double deg) {
        deg %= 360;
        if (deg >= 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }

    private static float clamp(double v, double lo, double hi) {
        if (!Double.isFinite(v)) return 0;
        return (float) Math.max(lo, Math.min(hi, v));
    }
}
