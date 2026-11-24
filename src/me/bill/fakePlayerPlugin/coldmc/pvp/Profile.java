package me.bill.fakePlayerPlugin.coldmc.pvp;

import org.bukkit.configuration.ConfigurationSection;

import java.util.concurrent.ThreadLocalRandom;

/**
 * How good one bot is. Every number is a human limitation the bot is held to, whatever its brain
 * (heuristic or trained model) would like to do - this is what keeps the bots beatable.
 */
public final class Profile {
    public final String id;
    /** Perception lag: the bot reacts to where things were this long ago. */
    public final int reactMinMs, reactMaxMs;
    /** Standard deviation of the aim offset, degrees. Grows while the target strafes. */
    public final double aimError;
    /** Fastest the head turns, degrees per tick. */
    public final double turnSpeed;
    /** Clicks per second, a range: a new rate is drawn every couple of seconds. */
    public final double cpsMin, cpsMax;
    /** A swing that is simply a whiff, even when aimed. */
    public final double missChance;
    /** Longest reach the bot will try, blocks (vanilla survival is 3.0). */
    public final double reach;
    /** 0..1: how much and how well it strafes. */
    public final double strafe;
    /** 0..1: chance to sprint-reset (w-tap) after a hit for extra knockback. */
    public final double wtap;
    /** 0..1: chance to jump for a critical hit when it could. */
    public final double crit;
    /** 0..1: chases and trades (1) or keeps its distance and retreats when hurt (0). */
    public final double aggression;
    /** Health share at which it starts drinking soup. */
    public final double soupAt;
    /** Ticks it takes per soup and per refill of the hotbar. */
    public final int soupMinTicks, soupMaxTicks, refillMinTicks, refillMaxTicks;
    /** 0..1 chance per second of a mistake: hesitating, overrunning, the wrong strafe, losing sprint. */
    public final double mistake;
    /** 0..1: how much of the decision comes from the trained model (the rest is the heuristic). */
    public final double modelWeight;
    /** Sampling temperature: above 1 = more random choices. */
    public final double temperature;
    /** 0..1: sticks with its target rather than turning on whoever hit it last. */
    public final double focus;
    /** Chance per second to use a kit's ability item when a target is near. */
    public final double ability;

    private Profile(String id, ConfigurationSection c, Profile d) {
        this.id = id;
        int[] react = range(c, "reaction-ms", d == null ? new int[]{250, 400} : new int[]{d.reactMinMs, d.reactMaxMs});
        reactMinMs = react[0]; reactMaxMs = react[1];
        aimError = clamp(c.getDouble("aim-error-deg", d == null ? 6 : d.aimError), 0.5, 45);
        turnSpeed = clamp(c.getDouble("turn-speed", d == null ? 18 : d.turnSpeed), 3, 60);
        double[] cps = rangeD(c, "cps", d == null ? new double[]{6, 9} : new double[]{d.cpsMin, d.cpsMax});
        cpsMin = clamp(cps[0], 1, 16); cpsMax = clamp(Math.max(cps[0], cps[1]), 1, 16);
        missChance = clamp(c.getDouble("miss-chance", d == null ? 0.15 : d.missChance), 0, 0.95);
        reach = clamp(c.getDouble("reach", d == null ? 2.9 : d.reach), 1.5, 3.0);
        strafe = clamp(c.getDouble("strafe", d == null ? 0.5 : d.strafe), 0, 1);
        wtap = clamp(c.getDouble("w-tap", d == null ? 0.3 : d.wtap), 0, 1);
        crit = clamp(c.getDouble("crit", d == null ? 0.3 : d.crit), 0, 1);
        aggression = clamp(c.getDouble("aggression", d == null ? 0.6 : d.aggression), 0, 1);
        soupAt = clamp(c.getDouble("soup-at", d == null ? 0.45 : d.soupAt), 0.05, 0.95);
        int[] soup = range(c, "soup-ticks", d == null ? new int[]{6, 12} : new int[]{d.soupMinTicks, d.soupMaxTicks});
        soupMinTicks = Math.max(2, soup[0]); soupMaxTicks = Math.max(soupMinTicks, soup[1]);
        int[] refill = range(c, "refill-ticks", d == null ? new int[]{20, 40} : new int[]{d.refillMinTicks, d.refillMaxTicks});
        refillMinTicks = Math.max(5, refill[0]); refillMaxTicks = Math.max(refillMinTicks, refill[1]);
        mistake = clamp(c.getDouble("mistakes", d == null ? 0.15 : d.mistake), 0, 1);
        modelWeight = clamp(c.getDouble("model-weight", d == null ? 0.6 : d.modelWeight), 0, 1);
        temperature = clamp(c.getDouble("temperature", d == null ? 1.0 : d.temperature), 0.5, 3);
        focus = clamp(c.getDouble("focus", d == null ? 0.6 : d.focus), 0, 1);
        ability = clamp(c.getDouble("ability-use", d == null ? 0.2 : d.ability), 0, 1);
    }

    public static Profile of(String id, ConfigurationSection c, Profile defaults) { return new Profile(id, c, defaults); }

    public int reactionTicks() {
        int ms = reactMinMs + (reactMaxMs > reactMinMs ? ThreadLocalRandom.current().nextInt(reactMaxMs - reactMinMs + 1) : 0);
        return Math.max(1, Math.round(ms / 50f));
    }

    public double cps() { return cpsMin + ThreadLocalRandom.current().nextDouble() * (cpsMax - cpsMin); }

    public static int between(int a, int b) { return b <= a ? a : a + ThreadLocalRandom.current().nextInt(b - a + 1); }

    @Override public String toString() {
        return id + " (react " + reactMinMs + "-" + reactMaxMs + " ms, aim ±" + aimError + "°, " + cpsMin + "-" + cpsMax
                + " cps, miss " + Math.round(missChance * 100) + "%, reach " + reach + ")";
    }

    private static double clamp(double v, double lo, double hi) { return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo; }

    private static int[] range(ConfigurationSection c, String key, int[] def) {
        if (c.isConfigurationSection(key)) return new int[]{c.getInt(key + ".min", def[0]), Math.max(c.getInt(key + ".min", def[0]), c.getInt(key + ".max", def[1]))};
        if (c.isList(key) && c.getIntegerList(key).size() >= 2) { var l = c.getIntegerList(key); return new int[]{l.get(0), Math.max(l.get(0), l.get(1))}; }
        if (c.contains(key)) { int v = c.getInt(key); return new int[]{v, v}; }
        return def;
    }

    private static double[] rangeD(ConfigurationSection c, String key, double[] def) {
        if (c.isConfigurationSection(key)) return new double[]{c.getDouble(key + ".min", def[0]), c.getDouble(key + ".max", def[1])};
        if (c.isList(key) && c.getDoubleList(key).size() >= 2) { var l = c.getDoubleList(key); return new double[]{l.get(0), l.get(1)}; }
        if (c.contains(key)) { double v = c.getDouble(key); return new double[]{v, v}; }
        return def;
    }
}
