package me.bill.fakePlayerPlugin.coldmc.pvp;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;

/**
 * One PvP bot's mind. Ticked every server tick on the main thread by {@link PvpBots}.
 *
 * <p>The bot plays through the same inputs a client sends: where it looks, which movement keys it
 * holds, sprint, jump, left click (the server's own attack code does the damage), right click (the
 * real interact event, so KitPvP's soup and abilities handle it). It never edits health, never
 * teleports itself and never moves faster than walking/sprinting allows.
 *
 * <p>What makes it beatable, all from its {@link Profile}: it sees the world {@code reaction-ms}
 * late, its aim carries a drifting error that grows when you strafe, its head turns at a limited
 * speed, it clicks at a human rate with jitter and whiffs some swings, its reach is below the
 * maximum, it holds keys for a while instead of reacting every tick, and it makes mistakes.
 */
final class BotBrain {
    private final PvpBots svc;
    final FakePlayer fp;
    final UUID id;
    Profile profile;
    /** Assigned by auto-fill (it may remove the bot) rather than by a command. */
    final boolean autoFill;
    /** A forced opponent from /cpp pvp fight, regardless of arena or world rules. */
    UUID forcedTarget;

    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();

    // target and perception
    private UUID target;
    private long targetPickedAt;
    private long nextTargetScan;
    private final ArrayDeque<double[]> seen = new ArrayDeque<>(); // target x,y,z per tick, newest last
    private int reaction = 6;
    private long nextReactionRoll;

    // aim
    private double offYaw, offPitch, wantOffYaw, wantOffPitch;
    private long nextAimDrift;

    // decisions (keys are held, not re-pressed every tick)
    private final ArrayDeque<float[]> obs = new ArrayDeque<>();
    private int fwd, strafe = 0;
    private boolean sprint, wantJump, wantAttack, wantHeal;
    private long holdMoveUntil, holdStrafeUntil;
    private int strafeSide = 1;

    // timing
    private long nextSwingAt;
    private double cps;
    private long nextCpsRoll;
    private long wtapUntil;
    private long mistakeUntil;
    private int mistakeKind;
    private long healBusyUntil, healFireAt;
    private boolean healing;
    private long nextAbilityAt;
    private long abilityFireAt = -1;
    private int abilitySlot = -1;

    // kit and arena
    private long pickKitAt = -1;
    private long nextJoinTry;
    private long nextWanderPick;
    private Location wanderTo;

    // stuck detection
    private Location lastPos;
    private int stuck;

    BotBrain(PvpBots svc, FakePlayer fp, Profile profile, boolean autoFill) {
        this.svc = svc;
        this.fp = fp;
        this.id = fp.getUuid();
        this.profile = profile;
        this.autoFill = autoFill;
        this.cps = profile.cps();
        // A new bot enters its MMOProfiles profile a few seconds after it spawns, and KitPvP ends a
        // session on a profile switch: joining before that was undone (dev test, 2026-11-23).
        this.nextJoinTry = svc.tick() + 200;
    }

    UUID target() { return target; }

    private boolean jumped, swung, souped;

    /** What the brain did since the last call, as recorder labels (only for learning.test-record-bots). */
    int[] intent() {
        int[] y = {fwd, strafe, jumped ? 1 : 0, sprint ? 1 : 0, swung ? 1 : 0, souped ? 1 : 0};
        jumped = swung = souped = false;
        return y;
    }

    /** Releases every key. Called when the brain stops or pauses. */
    /** Idle: no keys held, no target, no half-finished heal or ability; picks up afresh when woken. */
    void sleep(Player bot) {
        release(bot);
        target = null;
        healing = false;
        wantHeal = false;
        abilityFireAt = -1;
        seen.clear();
    }

    void release(Player bot) {
        if (bot == null) return;
        NmsPlayerSpawner.setMovementForward(bot, 0f);
        NmsPlayerSpawner.setMovementStrafe(bot, 0f);
        NmsPlayerSpawner.setJumping(bot, false);
        bot.setSprinting(false);
        fwd = 0; strafe = 0; sprint = false; wantJump = false;
    }

    void tick(long now) {
        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline() || bot.isDead()) return;
        if (fp.isFrozen()) { release(bot); return; }
        KitPvpHook kp = svc.kitpvp();

        // ---- KitPvP: be in the arena, with a kit
        if (kp != null && forcedTarget == null) {
            if (!kp.inGame(bot)) {
                release(bot);
                if (now >= nextJoinTry) {
                    nextJoinTry = now + 100;
                    svc.tryJoin(bot);
                }
                return;
            }
            if (kp.inDuel(bot)) { release(bot); return; } // duels are not for bots
            if (!kp.hasKit(bot)) {
                release(bot);
                target = null;
                healing = false;
                if (pickKitAt < 0) pickKitAt = now + svc.settings().pickKitTicks();
                else if (now >= pickKitAt) { pickKitAt = -1; svc.pickKit(bot); selectWeapon(bot); }
                return;
            }
            pickKitAt = -1;
        }

        Player t = resolveTarget(bot, now, kp);
        if (t == null) { wander(bot, now); return; }

        // ---- perception lag
        Location tl = t.getLocation();
        seen.addLast(new double[]{tl.getX(), tl.getY(), tl.getZ()});
        while (seen.size() > 40) seen.removeFirst();
        if (now >= nextReactionRoll) { reaction = profile.reactionTicks(); nextReactionRoll = now + 30 + rnd.nextInt(30); }
        double[] lag = lagged(reaction);

        Location me = bot.getLocation();
        double dx = lag[0] - me.getX(), dz = lag[2] - me.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        double realDist = Math.sqrt(sq(tl.getX() - me.getX()) + sq(tl.getZ() - me.getZ()));

        // ---- decide (every 2 ticks, on what the bot saw `reaction` ticks ago)
        if (now % 2 == 0) decide(bot, t, now, kp);

        // ---- aim: chest of the lagged target, plus a drifting error, at a capped turn speed
        aim(bot, lag, flat, now);

        // ---- healing has priority over everything but moving
        if (wantHeal || healing) heal(bot, now);

        // ---- abilities from kit items
        abilities(bot, t, realDist, now);

        // ---- move
        move(bot, me, now);

        // ---- attack
        if (!healing && abilityFireAt < 0) attack(bot, t, now);
    }

    // ================================================================== target

    private Player resolveTarget(Player bot, long now, KitPvpHook kp) {
        if (forcedTarget != null) {
            Player f = Bukkit.getPlayer(forcedTarget);
            if (f != null && f.isOnline() && !f.isDead() && f.getWorld() == bot.getWorld()) { setTarget(f, now); return f; }
            forcedTarget = null;
        }
        Player cur = target == null ? null : Bukkit.getPlayer(target);
        double range = svc.settings().targetRange;
        if (cur != null && !valid(bot, cur, kp, range * 1.3)) cur = null;
        if (cur != null && now < nextTargetScan) return cur;
        nextTargetScan = now + 10 + rnd.nextInt(10);
        Player best = cur;
        double bestScore = cur == null ? Double.MAX_VALUE : score(bot, cur) - 3; // stickiness
        for (Player p : svc.candidates(bot)) {
            if (p == cur || !valid(bot, p, kp, range)) continue;
            double s = score(bot, p);
            if (s < bestScore && bot.hasLineOfSight(p)) { bestScore = s; best = p; }
        }
        if (best == null) target = null; // nobody valid: "/cpp pvp list" must not keep showing the old target
        else if (best != cur) setTarget(best, now);
        return best;
    }

    private void setTarget(Player p, long now) {
        if (p == null) { target = null; return; }
        if (!p.getUniqueId().equals(target)) {
            target = p.getUniqueId();
            targetPickedAt = now;
            seen.clear();
            obs.clear();
            reaction = profile.reactionTicks() + 2; // noticing someone new is slower than tracking
        }
    }

    private double score(Player bot, Player p) {
        double d = bot.getLocation().distance(p.getLocation());
        // aggressive bots prefer weak targets; everyone is a little random
        return d - profile.aggression * 6 * (1 - CombatView.hp(p)) + rnd.nextDouble() * 3;
    }

    private boolean valid(Player bot, Player p, KitPvpHook kp, double range) {
        if (p == null || p == bot || !p.isOnline() || p.isDead() || p.getWorld() != bot.getWorld()) return false;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.isInvisible() && rnd.nextDouble() < 0.7) return false;
        if (bot.getLocation().distanceSquared(p.getLocation()) > range * range) return false;
        if (svc.noFightAt(bot, p.getLocation())) return false;
        if (kp != null && kp.inGame(bot)) return kp.sameArena(bot, p) && kp.fightable(p);
        return true;
    }

    /** Called by PvpBots when someone hits this bot: maybe turn on them. */
    void hurtBy(Player attacker, long now) {
        if (attacker == null || attacker.getUniqueId().equals(target) || forcedTarget != null) return;
        if (!svc.mayTarget(fp.getPlayer(), attacker)) return;
        if (target == null || rnd.nextDouble() > profile.focus) { setTarget(attacker, now); nextTargetScan = now + 40; }
    }

    private double[] lagged(int ticks) {
        int i = Math.max(0, seen.size() - 1 - ticks), k = 0;
        for (double[] v : seen) { if (k++ == i) return v; }
        return seen.peekLast();
    }

    // ================================================================== decide

    private void decide(Player bot, Player t, long now, KitPvpHook kp) {
        float[] f = svc.view().features(bot, t, svc.enemiesNear(bot, t), now, svc::soupCount);
        obs.addLast(f);
        while (obs.size() > 20) obs.removeFirst();
        int back = Math.min(obs.size() - 1, Math.max(0, reaction / 2));
        float[] o = f;
        int k = 0, want = obs.size() - 1 - back;
        for (float[] v : obs) { if (k++ == want) { o = v; break; } }

        double[] p = heuristic(o);
        Mlp m = svc.model();
        if (m != null && profile.modelWeight > 0) {
            double[] q = Mlp.probabilities(m.forward(o));
            double w = profile.modelWeight;
            for (int i = 0; i < p.length; i++) p[i] = (1 - w) * p[i] + w * q[i];
        }

        double dist = o[0] * 4;
        if (now >= holdMoveUntil) {
            fwd = pick3(p, PvpSchema.headOffset(0)) - 1;
            holdMoveUntil = now + 2 + rnd.nextInt(5);
        }
        if (now >= holdStrafeUntil) {
            int s = pick3(p, PvpSchema.headOffset(1)) - 1;
            // a skilled bot keeps a strafe going and reverses it rhythmically; a rookie wanders
            if (s != 0 && rnd.nextDouble() < profile.strafe) s = strafeSide;
            if (rnd.nextDouble() < 0.12 + 0.2 * profile.strafe) strafeSide = -strafeSide;
            strafe = s;
            holdStrafeUntil = now + (int) (6 + (1 - profile.strafe) * 10 + rnd.nextInt(10));
        }
        sprint = rnd.nextDouble() < temper(p[PvpSchema.headOffset(3)]);
        wantJump = rnd.nextDouble() < temper(p[PvpSchema.headOffset(2)]);
        wantAttack = rnd.nextDouble() < temper(p[PvpSchema.headOffset(4)]);
        if (!healing) wantHeal = rnd.nextDouble() < temper(p[PvpSchema.headOffset(5)]) && CombatView.hp(bot) < 0.98;

        // ---- mistakes, rolled per second
        if (now >= mistakeUntil && rnd.nextDouble() < profile.mistake / 10.0) {
            mistakeKind = rnd.nextInt(4);
            mistakeUntil = now + 6 + rnd.nextInt(14);
        }
        if (now < mistakeUntil) {
            switch (mistakeKind) {
                case 0 -> { fwd = 0; strafe = 0; }           // hesitates
                case 1 -> { if (dist < 2.5) fwd = 1; }      // runs into them
                case 2 -> strafe = -strafe;                  // strafes the wrong way
                default -> sprint = false;                   // forgets to sprint
            }
        }
    }

    /** Hand-written fallback policy, laid out like the model's output. Also the anchor the model is blended with. */
    private double[] heuristic(float[] f) {
        double[] p = new double[PvpSchema.OUT];
        double dist = f[0] * 4, myHp = f[5], oppHp = f[6], ready = f[11], soup = f[18], aimCos = f[3];
        boolean ground = f[16] > 0.5;
        boolean retreat = myHp < 0.3 && soup <= 0.01 && oppHp > myHp && profile.aggression < 0.9;
        double[] fw;
        if (retreat && rnd.nextDouble() > profile.aggression) fw = new double[]{0.75, 0.15, 0.10};
        else if (dist > 3.0) fw = new double[]{0.02, 0.06, 0.92};
        else if (dist < 1.6) fw = new double[]{0.35, 0.35, 0.30};
        else fw = new double[]{0.08 + 0.15 * (1 - profile.aggression), 0.22, 0.70 - 0.15 * (1 - profile.aggression)};
        System.arraycopy(fw, 0, p, PvpSchema.headOffset(0), 3);
        double sOn = dist < 6 ? 0.25 + 0.6 * profile.strafe : 0.08;
        p[PvpSchema.headOffset(1)] = sOn / 2;
        p[PvpSchema.headOffset(1) + 1] = 1 - sOn;
        p[PvpSchema.headOffset(1) + 2] = sOn / 2;
        p[PvpSchema.headOffset(2)] = ground && dist < 3.6 && ready > 0.7 ? 0.5 * profile.crit : 0.01;
        p[PvpSchema.headOffset(3)] = dist > 2.2 ? 0.95 : 0.55;
        p[PvpSchema.headOffset(4)] = dist <= profile.reach + 0.4 && aimCos > 0.93 ? 0.9 : (dist < 5 ? 0.12 : 0.02);
        p[PvpSchema.headOffset(5)] = myHp < profile.soupAt && soup > 0 ? 0.85 : 0.0;
        return p;
    }

    private int pick3(double[] p, int off) {
        double a = Math.pow(p[off], 1 / profile.temperature), b = Math.pow(p[off + 1], 1 / profile.temperature),
                c = Math.pow(p[off + 2], 1 / profile.temperature);
        double r = rnd.nextDouble() * (a + b + c);
        return r < a ? 0 : r < a + b ? 1 : 2;
    }

    /** A probability pushed toward 0.5 by temperature above 1. */
    private double temper(double p) {
        p = Math.max(1e-4, Math.min(1 - 1e-4, p));
        double logit = Math.log(p / (1 - p)) / profile.temperature;
        return 1 / (1 + Math.exp(-logit));
    }

    // ================================================================== aim

    private void aim(Player bot, double[] lag, double flat, long now) {
        if (now >= nextAimDrift) {
            double lateral = Math.abs(svc.view().velocity(target)[0]) + Math.abs(svc.view().velocity(target)[1]);
            double err = profile.aimError * (1 + lateral * 3);
            wantOffYaw = rnd.nextGaussian() * err;
            wantOffPitch = rnd.nextGaussian() * err * 0.5;
            nextAimDrift = now + 6 + rnd.nextInt(10);
        }
        offYaw += (wantOffYaw - offYaw) * 0.25;
        offPitch += (wantOffPitch - offPitch) * 0.25;

        Location eye = bot.getEyeLocation();
        double dx = lag[0] - eye.getX(), dz = lag[2] - eye.getZ();
        double dy = (lag[1] + 1.1) - eye.getY();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz)) + offYaw;
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.max(0.1, flat))) + offPitch;
        Location cur = bot.getLocation();
        double maxTurn = profile.turnSpeed * (0.7 + rnd.nextDouble() * 0.6);
        double dYaw = CombatView.wrap(yaw - cur.getYaw());
        double dPitch = pitch - cur.getPitch();
        float ny = (float) (cur.getYaw() + Math.max(-maxTurn, Math.min(maxTurn, dYaw)));
        float np = (float) Math.max(-89, Math.min(89, cur.getPitch() + Math.max(-maxTurn, Math.min(maxTurn, dPitch))));
        bot.setRotation(ny, np);
        NmsPlayerSpawner.setHeadYaw(bot, ny);
    }

    // ================================================================== move

    private void move(Player bot, Location me, long now) {
        int f = fwd, s = strafe;
        boolean spr = sprint && f > 0 && now >= wtapUntil && bot.getFoodLevel() > 6;
        // never walk off a ledge, into lava or into the void
        if (f != 0 || s != 0) {
            Vector dir = worldDir(me.getYaw(), f, s);
            if (unsafe(bot.getWorld(), me, dir)) {
                if (f != 0 && unsafe(bot.getWorld(), me, worldDir(me.getYaw(), f, 0))) f = 0;
                if (s != 0 && unsafe(bot.getWorld(), me, worldDir(me.getYaw(), 0, s))) s = 0;
                spr = false;
            }
        }
        // stuck against a block: hop
        boolean hop = false;
        if (lastPos != null && lastPos.getWorld() == me.getWorld() && (f != 0 || s != 0)) {
            double moved = sq(me.getX() - lastPos.getX()) + sq(me.getZ() - lastPos.getZ());
            stuck = moved < 0.0009 && bot.isOnGround() ? stuck + 1 : 0;
            if (stuck >= 4) { hop = true; stuck = 0; }
        }
        lastPos = me.clone();

        NmsPlayerSpawner.setMovementForward(bot, f);
        NmsPlayerSpawner.setMovementStrafe(bot, -s); // NMS: positive strafe = left
        bot.setSprinting(spr);
        boolean jump = (wantJump || hop) && bot.isOnGround();
        NmsPlayerSpawner.setJumping(bot, jump);
        if (jump) { wantJump = false; jumped = true; }
    }

    /** Horizontal unit direction for forward/strafe inputs at a yaw. */
    private static Vector worldDir(float yaw, int f, int s) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        double rx = -fz, rz = fx; // right-hand side
        Vector v = new Vector(fx * f + rx * s, 0, fz * f + rz * s);
        return v.lengthSquared() < 1e-6 ? v : v.normalize();
    }

    private boolean unsafe(World w, Location me, Vector dir) {
        if (dir.lengthSquared() < 1e-6) return false;
        Location ahead = me.clone().add(dir.clone().multiply(0.9));
        Block feet = ahead.getBlock();
        if (bad(feet.getType()) || bad(feet.getRelative(BlockFace.DOWN).getType())) return true;
        int maxDrop = svc.settings().maxSafeDrop;
        Block b = feet;
        for (int i = 0; i <= maxDrop; i++) {
            b = b.getRelative(BlockFace.DOWN);
            if (b.getY() < w.getMinHeight()) return true;
            if (bad(b.getType())) return true;
            if (b.getType().isSolid() || b.isLiquid()) return false;
        }
        return true; // nothing to land on within max-safe-drop
    }

    private static boolean bad(Material m) {
        return m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.MAGMA_BLOCK
                || m == Material.CACTUS || m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW || m == Material.WITHER_ROSE;
    }

    private void wander(Player bot, long now) {
        if (wanderTo == null || now >= nextWanderPick || wanderTo.getWorld() != bot.getWorld()
                || bot.getLocation().distanceSquared(wanderTo) < 2.5) {
            Location base = svc.wanderCentre(bot);
            if (base == null) { release(bot); return; }
            double inner = svc.wanderInner(bot);
            double a = rnd.nextDouble() * Math.PI * 2, r = inner + rnd.nextDouble() * 14;
            wanderTo = base.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            nextWanderPick = now + 100 + rnd.nextInt(100);
        }
        Location me = bot.getLocation();
        double dx = wanderTo.getX() - me.getX(), dz = wanderTo.getZ() - me.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double d = CombatView.wrap(yaw - me.getYaw());
        float ny = (float) (me.getYaw() + Math.max(-8, Math.min(8, d)));
        bot.setRotation(ny, (float) (me.getPitch() * 0.9));
        NmsPlayerSpawner.setHeadYaw(bot, ny);
        fwd = Math.abs(d) < 60 ? 1 : 0;
        strafe = 0;
        sprint = false;
        wantJump = false;
        move(bot, me, now);
    }

    // ================================================================== attack

    private void attack(Player bot, Player t, long now) {
        if (now >= nextCpsRoll) { cps = profile.cps(); nextCpsRoll = now + 40 + rnd.nextInt(40); }
        if (now < nextSwingAt) return;
        if (svc.noFightAt(bot, bot.getLocation()) || svc.noFightAt(bot, t.getLocation())) return;
        double reachNow = profile.reach - rnd.nextDouble() * 0.2;
        RayTraceResult hit = rayHit(bot, t, reachNow);
        // swings come from the decision, but in reach and on target a player clicks more
        boolean go = wantAttack || (hit != null && rnd.nextDouble() < 0.6);
        if (!go) return;
        double interval = Math.max(20.0 / cps, CombatView.attackIntervalTicks(bot) * (0.9 + rnd.nextDouble() * 0.25));
        nextSwingAt = now + Math.max(1, Math.round(interval * (0.8 + rnd.nextDouble() * 0.4)));
        wantAttack = false;
        bot.swingMainHand();
        swung = true;
        svc.view().swung(id, now);
        if (hit == null || rnd.nextDouble() < profile.missChance) return; // a whiff
        boolean wasSprinting = bot.isSprinting();
        NmsPlayerSpawner.performAttack(bot, t, 1.0);
        if (wasSprinting && rnd.nextDouble() < profile.wtap) wtapUntil = now + 2 + rnd.nextInt(3);
    }

    /** Does the bot's actual look ray hit the target's hitbox within reach? Blocks in between stop it. */
    private static RayTraceResult rayHit(Player bot, Player t, double reach) {
        Location eye = bot.getEyeLocation();
        Vector dir = eye.getDirection();
        BoundingBox box = t.getBoundingBox().expand(0.1);
        RayTraceResult r = box.rayTrace(eye.toVector(), dir, reach);
        if (r == null) return null;
        RayTraceResult wall = bot.getWorld().rayTraceBlocks(eye, dir, eye.toVector().distance(r.getHitPosition()));
        return wall != null && wall.getHitBlock() != null ? null : r;
    }

    // ================================================================== soup

    private void heal(Player bot, long now) {
        PlayerInventory inv = bot.getInventory();
        if (!healing) {
            if (CombatView.hp(bot) >= 0.98) { wantHeal = false; return; }
            int slot = soupSlot(inv);
            if (slot < 0) {
                if (!refill(inv)) { wantHeal = false; return; }
                healing = true; // refilling takes a while, like opening the inventory
                healFireAt = -1;
                healBusyUntil = now + Profile.between(profile.refillMinTicks, profile.refillMaxTicks);
                return;
            }
            inv.setHeldItemSlot(slot);
            healing = true;
            healFireAt = now + Profile.between(profile.soupMinTicks, profile.soupMaxTicks) / 2;
            healBusyUntil = now + Profile.between(profile.soupMinTicks, profile.soupMaxTicks);
            return;
        }
        if (healFireAt >= 0 && now >= healFireAt) {
            healFireAt = -1;
            ItemStack hand = inv.getItemInMainHand();
            if (svc.isSoup(hand)) { rightClick(bot, hand); souped = true; }
        }
        if (now >= healBusyUntil) {
            healing = false;
            wantHeal = CombatView.hp(bot) < profile.soupAt && svc.soupCount(bot) > 0 && rnd.nextDouble() < 0.8;
            if (!wantHeal) selectWeapon(bot);
        }
    }

    private int soupSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) if (svc.isSoup(inv.getItem(i))) return i;
        return -1;
    }

    /** Moves soup from the inventory into the hotbar, over bowls and empty slots. False if there is none. */
    private boolean refill(PlayerInventory inv) {
        boolean moved = false;
        for (int h = 0; h < 9; h++) {
            ItemStack it = inv.getItem(h);
            if (it != null && !it.getType().isAir() && it.getType() != Material.BOWL) continue;
            for (int i = 9; i < 36; i++) {
                if (!svc.isSoup(inv.getItem(i))) continue;
                inv.setItem(h, inv.getItem(i));
                inv.setItem(i, it != null && it.getType() == Material.BOWL ? null : it);
                moved = true;
                break;
            }
        }
        return moved;
    }

    void selectWeapon(Player bot) {
        PlayerInventory inv = bot.getInventory();
        int best = inv.getHeldItemSlot();
        double bestDmg = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || svc.isSoup(it) || CombatView.isRanged(it)) continue;
            double d = CombatView.weaponDamage(it);
            if (d > bestDmg) { bestDmg = d; best = i; }
        }
        if (bestDmg > 1) inv.setHeldItemSlot(best);
    }

    // ================================================================== kit abilities

    private void abilities(Player bot, Player t, double dist, long now) {
        if (abilityFireAt >= 0) {
            if (now >= abilityFireAt) {
                abilityFireAt = -1;
                ItemStack it = bot.getInventory().getItem(abilitySlot);
                if (it != null && !it.getType().isAir()) rightClick(bot, it);
                selectWeapon(bot);
            }
            return;
        }
        if (healing || now < nextAbilityAt || dist > 10 || dist < 1.5) return;
        nextAbilityAt = now + 20;
        if (rnd.nextDouble() >= profile.ability) return;
        List<Integer> slots = new java.util.ArrayList<>();
        PlayerInventory inv = bot.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || svc.isSoup(it) || it.getType() == Material.BOWL) continue;
            if (CombatView.weaponDamage(it) > 1 || CombatView.isRanged(it) || it.getType().isEdible() || it.getType().isBlock()) continue;
            slots.add(i);
        }
        if (slots.isEmpty()) { nextAbilityAt = now + 200; return; }
        abilitySlot = slots.get(rnd.nextInt(slots.size()));
        inv.setHeldItemSlot(abilitySlot);
        abilityFireAt = now + 3 + rnd.nextInt(5);
        nextAbilityAt = now + 80 + rnd.nextInt(160);
    }

    /** The interact event a client's right click produces, so KitPvP's soup and ability handlers run as for a player. */
    private static void rightClick(Player bot, ItemStack item) {
        PlayerInteractEvent e = new PlayerInteractEvent(bot, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(e);
        bot.swingMainHand();
    }

    private static double sq(double v) { return v * v; }
}
