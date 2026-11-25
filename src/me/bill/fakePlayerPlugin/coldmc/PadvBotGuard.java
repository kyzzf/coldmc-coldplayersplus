package me.bill.fakePlayerPlugin.coldmc;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;

/**
 * Keeps bots out of PremiumAdvancements' PLAYTIME trigger (ColdMC, 2026-11-25).
 *
 * <p>PAdv 3.1 times every online player from its own join listener: a map of session start
 * times and a map of stored minutes, both keyed by uuid. Every 30 s it asks UltimateAdvancementAPI
 * whether each player who has passed an advancement's minutes already has it. UAA never loads a
 * bot, so the ask throws, and production logged {@code ERROR completing q_tenhours for <bot>: Could
 * not load user fb07...} for every long-lived alt, twice a minute.
 *
 * <p>Once a second this takes every bot out of both maps. PAdv reads a missing entry as 0 minutes
 * (both its check and its quit save are null-safe - javap of PAdv 3.1), so a bot never reaches the
 * failing ask, and its stored playtime is saved back as 0 on its next quit.
 *
 * <p>PAdv is obfuscated. The listener is found by its event method {@code onPlaytimeJoin} (an
 * annotated handler keeps its name), and the two maps by exact field names {@code f} (start) and
 * {@code g} (minutes). After a PAdv update that renames them, the guard logs once and stops.
 * Switch: {@code body.no-advancement-playtime} in config.yml (default true).
 */
public final class PadvBotGuard {
    private final FakePlayerPlugin plugin;
    private Plugin padv;
    private Object listener;
    private Field start, minutes;
    private boolean gaveUp;

    public PadvBotGuard(FakePlayerPlugin plugin) { this.plugin = plugin; }

    public void start() { Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 40L, 20L); }

    @SuppressWarnings("unchecked")
    private void sweep() {
        if (!plugin.getConfig().getBoolean("body.no-advancement-playtime", true)) return;
        var bots = plugin.getFakePlayerManager().getActivePlayers();
        if (bots.isEmpty()) return;
        Plugin p = Bukkit.getPluginManager().getPlugin("PremiumAdvancements");
        if (p == null || !p.isEnabled()) { listener = null; padv = null; return; }
        if (p != padv) { padv = p; listener = null; gaveUp = false; } // a reload or a new jar: look again
        if (listener == null && (gaveUp || !find(p))) return;
        try {
            Map<Object, Object> s = (Map<Object, Object>) start.get(listener);
            Map<Object, Object> m = (Map<Object, Object>) minutes.get(listener);
            for (FakePlayer fp : bots) { s.remove(fp.getUuid()); m.remove(fp.getUuid()); }
        } catch (Throwable t) {
            off("could not clear bots from its playtime maps (" + t + ")");
        }
    }

    private boolean find(Plugin p) {
        for (RegisteredListener rl : PlayerJoinEvent.getHandlerList().getRegisteredListeners()) {
            if (rl.getPlugin() != p) continue;
            Listener l = rl.getListener();
            try { l.getClass().getMethod("onPlaytimeJoin", PlayerJoinEvent.class); } catch (NoSuchMethodException e) { continue; }
            try {
                Field f = l.getClass().getDeclaredField("f"), g = l.getClass().getDeclaredField("g");
                if (!Map.class.isAssignableFrom(f.getType()) || !Map.class.isAssignableFrom(g.getType())
                        || Modifier.isStatic(f.getModifiers()) || Modifier.isStatic(g.getModifiers()))
                    return off("its playtime fields are not the maps they were in PAdv 3.1");
                f.setAccessible(true);
                g.setAccessible(true);
                start = f;
                minutes = g;
                listener = l;
                plugin.getLogger().info("Bots are kept out of PremiumAdvancements playtime advancements.");
                return true;
            } catch (NoSuchFieldException e) {
                return off("its playtime fields were renamed (PremiumAdvancements updated?)");
            }
        }
        return off("its playtime listener was not found");
    }

    private boolean off(String why) {
        if (!gaveUp) plugin.getLogger().warning("Bot playtime guard is OFF: PremiumAdvancements " + why
                + ". Bots may log 'ERROR completing' lines again.");
        gaveUp = true;
        listener = null;
        return false;
    }
}
