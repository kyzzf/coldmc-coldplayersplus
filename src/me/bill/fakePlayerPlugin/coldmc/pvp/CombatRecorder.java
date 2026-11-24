package me.bill.fakePlayerPlugin.coldmc.pvp;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import io.papermc.paper.event.player.PlayerArmSwingEvent;

/**
 * Records how real players fight, for the offline trainer. Main thread does only the cheap part
 * (one float[] per sample); a background task formats and writes. Nothing that identifies a player
 * is written: each is a salted 8-character tag. Bots are never recorded.
 *
 * <p>Only real fighting is kept (the owner, 2026-11-24): a sample is taken only while both players have
 * pressed a key, turned or swung within {@code afk-seconds}, and an engagement's samples are held
 * back until the player has both dealt {@code min-damage-dealt} and taken {@code min-damage-taken}.
 * One that never gets there - standing next to someone, an AFK player, a one-sided hit - is thrown
 * away with no row written. Fights against bots are left out unless {@code record-vs-bots}.
 *
 * <p>Rows (gzipped CSV, one file per {@code file-minutes}):
 * <pre>
 * S,v1,who,eng,&lt;features&gt;,&lt;labels&gt;      header, at the top of every file
 * S,&lt;who&gt;,&lt;eng&gt;,f1..fN,fwd,strafe,jump,sprint,attack,heal
 * E,&lt;who&gt;,&lt;eng&gt;,&lt;damage dealt&gt;,&lt;damage taken&gt;,win|loss|none
 * </pre>
 */
final class CombatRecorder implements Listener {
    /** A fight between one recorded player and one opponent. */
    private static final class Engagement {
        final long id;
        final UUID opp;
        long lastDamageTick;
        double dealt, taken;
        /** Samples held back until the fight is real (both sides hurt); then written straight away. */
        java.util.ArrayDeque<Object[]> held = new java.util.ArrayDeque<>();
        boolean live;
        Engagement(long id, UUID opp, long tick) { this.id = id; this.opp = opp; this.lastDamageTick = tick; }
    }

    /** Per recorded player: actions seen since the last sample, and the running engagement. */
    private static final class Watch {
        final String who;
        boolean swung, souped;
        Engagement eng;
        Watch(String who) { this.who = who; }
    }

    private static final long ENGAGEMENT_IDLE_TICKS = 200; // 10 s with no damage either way ends a fight
    private static final int MAX_HELD = 6000; // ten minutes of samples at the default rate

    private final PvpBots svc;
    private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final Map<UUID, Watch> watches = new HashMap<>();
    /** Starts at the clock so engagement ids never repeat across restarts (the trainer keys on player + id). */
    private final java.util.concurrent.atomic.AtomicLong engCounter = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    /** Last tick a real player pressed a key, turned their head, swung or right-clicked. */
    private final Map<UUID, Long> lastActive = new HashMap<>();
    private final Map<UUID, float[]> lastLook = new HashMap<>();
    private byte[] salt;
    private volatile boolean paused; // disk cap reached
    private volatile long samplesWritten, droppedFull;

    // writer state - background thread only
    private Writer out;
    private File outFile;
    private long outOpenedAt;
    private final Object writeLock = new Object();

    CombatRecorder(PvpBots svc) { this.svc = svc; }

    File folder() {
        return new File(svc.plugin().getDataFolder(), svc.settings().learningFolder + (svc.settings().testRecordBots ? "/observations-test" : "/observations"));
    }

    void start() {
        folder().mkdirs();
        loadSalt();
    }

    boolean paused() { return paused; }
    long written() { return samplesWritten; }
    long dropped() { return droppedFull; }
    int pending() { return queued.get(); }

    // ------------------------------------------------------------------ main thread

    /** Notes whether a real player is at the keyboard; called every sample tick for each one in KitPvP. */
    void noteActivity(Player p, long tick) {
        UUID id = p.getUniqueId();
        Input in = p.getCurrentInput();
        boolean active = in.isForward() || in.isBackward() || in.isLeft() || in.isRight() || in.isJump() || in.isSneak() || in.isSprint();
        Location l = p.getLocation();
        float[] look = lastLook.get(id);
        if (look == null) lastLook.put(id, look = new float[]{l.getYaw(), l.getPitch()});
        else if (Math.abs(l.getYaw() - look[0]) > 0.5f || Math.abs(l.getPitch() - look[1]) > 0.5f) { active = true; look[0] = l.getYaw(); look[1] = l.getPitch(); }
        if (active) lastActive.put(id, tick);
    }

    /** A real player who has done nothing for afk-seconds is AFK. Bots are never AFK. */
    boolean afk(Player p, long tick) {
        if (svc.isBot(p)) return false;
        Long t = lastActive.get(p.getUniqueId());
        return t == null || tick - t > svc.settings().afkTicks;
    }

    private void active(UUID id) { lastActive.put(id, svc.tick()); }

    /** Called every sample tick for every real player who is fighting. */
    void sample(Player p, Player opp, float[] features, long tick) { sample(p, opp, features, tick, null); }

    /** botIntent: labels for a bot under learning.test-record-bots (its own brain's choice); null for a real player. */
    void sample(Player p, Player opp, float[] features, long tick, int[] botIntent) {
        if (paused) return;
        Watch w = watches.computeIfAbsent(p.getUniqueId(), id -> new Watch(tag(id)));
        if (w.eng == null || !w.eng.opp.equals(opp.getUniqueId())) {
            endEngagement(w, "none");
            w.eng = new Engagement(engCounter.incrementAndGet(), opp.getUniqueId(), tick);
        }
        int[] y;
        if (botIntent != null) y = botIntent;
        else {
            Input in = p.getCurrentInput();
            int fwd = (in.isForward() ? 1 : 0) - (in.isBackward() ? 1 : 0);
            int strafe = (in.isRight() ? 1 : 0) - (in.isLeft() ? 1 : 0);
            y = new int[]{fwd, strafe, in.isJump() ? 1 : 0, (p.isSprinting() || in.isSprint()) ? 1 : 0, w.swung ? 1 : 0, w.souped ? 1 : 0};
        }
        w.swung = false;
        w.souped = false;
        Object[] row = {w.who, w.eng.id, features, y};
        if (w.eng.live) offer(row);
        else {
            w.eng.held.addLast(row);
            if (w.eng.held.size() > MAX_HELD) w.eng.held.removeFirst();
        }
    }

    /** Once the player has both dealt and taken enough damage, the fight counts: write what was held back. */
    private void checkLive(Watch w) {
        Engagement e = w.eng;
        if (e == null || e.live || e.dealt < svc.settings().minDealt || e.taken < svc.settings().minTaken) return;
        e.live = true;
        for (Object[] r : e.held) offer(r);
        e.held = null;
    }

    /** The opponent this player is trading hits with (damage within the last 5 s), or null. */
    UUID focus(Player p, long tick) {
        Watch w = watches.get(p.getUniqueId());
        return w != null && w.eng != null && tick - w.eng.lastDamageTick < 100 ? w.eng.opp : null;
    }

    /** Ends engagements that went quiet; called once a second. */
    void sweep(long tick) {
        for (Watch w : watches.values())
            if (w.eng != null && tick - w.eng.lastDamageTick > ENGAGEMENT_IDLE_TICKS) endEngagement(w, "none");
    }

    private void endEngagement(Watch w, String result) {
        if (w.eng == null) return;
        Engagement e = w.eng;
        w.eng = null;
        if (!e.live) return; // never a real fight (one-sided, AFK or just standing near): its samples are dropped
        offer(String.format(Locale.ROOT, "E,%s,%d,%.1f,%.1f,%s", w.who, e.id, e.dealt, e.taken, result));
    }

    private void offer(Object row) {
        if (queued.get() >= svc.settings().maxQueue) {
            if (queue.poll() != null) queued.decrementAndGet();
            droppedFull++;
        }
        queue.add(row);
        queued.incrementAndGet();
    }

    private boolean recorded(Player p) { return p != null && watches.containsKey(p.getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerArmSwingEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        svc.view().swung(e.getPlayer().getUniqueId(), svc.tick());
        Watch w = watches.get(e.getPlayer().getUniqueId());
        if (w != null) w.swung = true;
        if (!svc.isBot(e.getPlayer())) active(e.getPlayer().getUniqueId());
    }

    /** MONITOR and not ignoring cancelled: KitPvP cancels the event when it heals with soup. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Watch w = watches.get(e.getPlayer().getUniqueId());
        if (w != null && svc.isSoup(e.getItem())) w.souped = true;
        if (!svc.isBot(e.getPlayer())) active(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = PvpBots.attackerOf(e.getDamager());
        if (attacker == null) return;
        double dmg = e.getFinalDamage();
        Watch wa = watches.get(attacker.getUniqueId());
        if (wa != null && wa.eng != null && wa.eng.opp.equals(victim.getUniqueId())) { wa.eng.dealt += dmg; wa.eng.lastDamageTick = svc.tick(); checkLive(wa); }
        Watch wv = watches.get(victim.getUniqueId());
        if (wv != null && wv.eng != null && wv.eng.opp.equals(attacker.getUniqueId())) { wv.eng.taken += dmg; wv.eng.lastDamageTick = svc.tick(); checkLive(wv); }
    }

    /** KitPvP calls deaths off (no death screen) at LOW, so cancelled deaths must be seen too. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        UUID dead = e.getEntity().getUniqueId();
        Watch wd = watches.get(dead);
        if (wd != null) endEngagement(wd, "loss");
        for (Watch w : watches.values())
            if (w.eng != null && w.eng.opp.equals(dead) && svc.tick() - w.eng.lastDamageTick < 100 && w.eng.dealt > 0) endEngagement(w, "win");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastActive.remove(id);
        lastLook.remove(id);
        Watch w = watches.remove(id);
        if (w != null) endEngagement(w, "none");
    }

    // ------------------------------------------------------------------ background thread

    /** Drains the queue to disk. Runs async; safe to call from shutdown too. */
    void flush(boolean closing) {
        synchronized (writeLock) {
            try {
                if (!queue.isEmpty()) {
                    long fileMs = svc.settings().fileMinutes * 60_000L;
                    if (out == null || System.currentTimeMillis() - outOpenedAt > fileMs) openNext();
                    Object row;
                    StringBuilder b = new StringBuilder(256);
                    while ((row = queue.poll()) != null) {
                        queued.decrementAndGet();
                        b.setLength(0);
                        if (row instanceof String s) b.append(s);
                        else {
                            Object[] r = (Object[]) row;
                            b.append("S,").append(r[0]).append(',').append(r[1]);
                            for (float v : (float[]) r[2]) b.append(',').append(fmt(v));
                            for (int v : (int[]) r[3]) b.append(',').append(v);
                            samplesWritten++;
                        }
                        out.write(b.append('\n').toString());
                    }
                    out.flush();
                }
                if (closing) closeOut();
                enforceDisk();
            } catch (IOException e) {
                svc.plugin().getLogger().warning("[pvp] could not write observations: " + e.getMessage());
                closeOut();
            }
        }
    }

    private void openNext() throws IOException {
        closeOut();
        File dir = folder();
        dir.mkdirs();
        String name = "obs-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv.gz";
        outFile = new File(dir, name);
        OutputStream os = new GZIPOutputStream(Files.newOutputStream(outFile.toPath()), 1 << 16, true);
        out = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        out.write(PvpSchema.header() + "\n");
        outOpenedAt = System.currentTimeMillis();
    }

    private void closeOut() {
        if (out == null) return;
        try { out.close(); } catch (IOException ignored) {}
        out = null;
        outFile = null;
    }

    /** Stops collecting at the disk cap; removes old files only when retention-days asks for it. */
    private void enforceDisk() {
        File[] files = folder().listFiles((d, n) -> n.endsWith(".csv.gz") || n.endsWith(".csv"));
        if (files == null) return;
        int days = svc.settings().retentionDays;
        long total = 0;
        for (File f : files) {
            if (days > 0 && f != outFile && System.currentTimeMillis() - f.lastModified() > days * 86_400_000L) {
                if (f.delete()) continue;
            }
            total += f.length();
        }
        boolean over = total > svc.settings().maxDiskMb * 1024L * 1024L;
        if (over && !paused) svc.plugin().getLogger().warning("[pvp] observation files reached learning.max-disk-mb (" + svc.settings().maxDiskMb
                + " MB): collection paused. Pull them for training and move them away, or raise the limit.");
        if (!over && paused) svc.plugin().getLogger().info("[pvp] observation folder is under its limit again: collection resumed.");
        paused = over;
    }

    // ------------------------------------------------------------------ privacy

    private void loadSalt() {
        File f = new File(svc.plugin().getDataFolder(), svc.settings().learningFolder + "/salt.key");
        try {
            if (f.exists()) salt = HexFormat.of().parseHex(Files.readString(f.toPath()).trim());
            if (salt == null || salt.length < 16) {
                salt = new byte[32];
                new SecureRandom().nextBytes(salt);
                f.getParentFile().mkdirs();
                Files.writeString(f.toPath(), HexFormat.of().formatHex(salt));
            }
        } catch (Exception e) {
            salt = new byte[32];
            new SecureRandom().nextBytes(salt);
        }
    }

    /** Salted hash of the UUID, 8 hex characters: tells players apart for balancing, says nothing about who. */
    private String tag(UUID id) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(id.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(), 0, 4);
        } catch (Exception e) {
            return "anon";
        }
    }

    private static String fmt(float v) {
        if (v == 0) return "0";
        if (v == 1) return "1";
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
