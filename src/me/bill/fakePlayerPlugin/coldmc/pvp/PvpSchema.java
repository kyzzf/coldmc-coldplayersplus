package me.bill.fakePlayerPlugin.coldmc.pvp;

/**
 * The one definition of what a combat observation contains - shared by the recorder (real players,
 * on the server), the bot brain (inference, on the server) and the {@link Trainer} (offline, on
 * the owner's computer). Pure Java on purpose: the trainer runs with plain {@code java -cp}.
 *
 * <p>Changing the list makes every saved model incompatible; bump {@link #VERSION} and a model
 * written for another version is refused (the bots fall back to the heuristic brain).
 */
public final class PvpSchema {
    private PvpSchema() {}

    public static final int VERSION = 1;

    /** Inputs, in order. Every value is already scaled to roughly -2..2 by the extractor. */
    public static final String[] FEATURES = {
        "dist",            // horizontal distance to the opponent / 4
        "dy",              // opponent y - my y, / 3
        "aim_sin",         // sin of (direction to opponent - my yaw): which side they are on
        "aim_cos",         // cos of the same: 1 = dead ahead
        "opp_facing",      // cos of (opponent's yaw - direction from them to me): 1 = they look at me
        "my_hp", "opp_hp", // health / max health
        "my_armor", "opp_armor",   // armour points / 20
        "my_weapon", "opp_weapon", // estimated melee damage of the held item / 10
        "my_ready",        // ticks since my last swing / my attack interval, capped 1.5
        "my_hurt", "opp_hurt",     // no-damage ticks / 20 (recently hit)
        "closing",         // horizontal speed toward each other, blocks per tick * 4
        "opp_lateral",     // opponent's sideways speed relative to the line between us, * 4
        "my_ground", "opp_ground",
        "my_soup",         // soups in my inventory / 8, capped 1
        "enemies",         // other fighters within 10 blocks / 4, capped 1
        "my_falling",      // fall distance / 2, capped 1 (a crit is possible)
        "opp_blocking",    // opponent is raising a shield
        "my_ranged"        // holding a bow, crossbow or trident
    };

    public static final int N = FEATURES.length;

    /** Output heads. Softmax heads have 3 classes (-1, 0, +1 as index 0, 1, 2); binary heads 1. */
    public static final String[] HEADS = {"fwd", "strafe", "jump", "sprint", "attack", "heal"};
    public static final int[] HEAD_SIZE = {3, 3, 1, 1, 1, 1};
    public static final int OUT = 10;

    /** Offset of each head inside the output vector. */
    public static int headOffset(int head) {
        int o = 0;
        for (int i = 0; i < head; i++) o += HEAD_SIZE[i];
        return o;
    }

    /** Column order of a label block in an observation row: fwd strafe jump sprint attack heal. */
    public static final int LABELS = HEADS.length;

    public static String header() {
        StringBuilder b = new StringBuilder("S,v").append(VERSION).append(",who,eng");
        for (String f : FEATURES) b.append(',').append(f);
        for (String h : HEADS) b.append(",y_").append(h);
        return b.toString();
    }
}
