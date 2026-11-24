package me.bill.fakePlayerPlugin.coldmc.pvp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.command.FppCommand;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;

/** /cpp pvp ... - the PvP bot controls (ColdMC). */
public final class PvpCommand implements FppCommand {
    private final PvpBots svc;

    public PvpCommand(PvpBots svc) { this.svc = svc; }

    @Override public String getName() { return "pvp"; }
    @Override public String getUsage() { return "<add|remove|spawn|despawn|profile|fight|list|model|data|reload>"; }
    @Override public String getDescription() { return "PvP bots: give bots a fighting brain, fill KitPvP, manage the learning."; }
    @Override public String getPermission() { return "cpp.pvp"; }

    @Override
    public boolean execute(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "add" -> {
                if (a.length < 2) return usage(s, "add <bot|all> [profile]");
                String profile = a.length > 2 ? a[2] : "default";
                if (!svc.settings().profiles.containsKey(profile.toLowerCase(Locale.ROOT))) { s.sendMessage("§cNo profile " + profile + ". Profiles: " + String.join(", ", svc.settings().profiles.keySet())); return true; }
                int n = 0;
                for (FakePlayer fp : bots(a[1])) if (svc.attach(fp, profile, false)) n++;
                s.sendMessage(n > 0 ? "§a" + n + " bot(s) now fight as " + profile + "." : "§7Nothing changed (unknown bot, already fighting, or max-bots " + svc.settings().maxBots + " reached).");
                svc.saveState();
            }
            case "remove" -> {
                if (a.length < 2) return usage(s, "remove <bot|all>");
                int n = 0;
                for (FakePlayer fp : bots(a[1])) if (svc.detach(fp)) n++;
                s.sendMessage("§a" + n + " bot(s) no longer fight (still online).");
                svc.saveState();
            }
            case "spawn" -> {
                int count = a.length > 1 ? parse(a[1], 1) : 1;
                String profile = a.length > 2 ? a[2] : "default";
                Location at = null;
                KitPvpHook kp = svc.kitpvp();
                if (kp != null) { String arena = kp.resolveArena(svc.settings().arena); if (arena != null) at = kp.arenaSpawn(arena); }
                if (at == null && s instanceof Player p) at = p.getLocation();
                if (at == null) { s.sendMessage("§cNo place to spawn: no KitPvP arena and you are not a player."); return true; }
                int n = svc.spawnBots(Math.max(1, Math.min(50, count)), profile, false, at);
                s.sendMessage(n > 0 ? "§a" + n + " PvP bot(s) on their way (" + svc.settings().profile(profile).id + ")." + (kp != null ? " They join KitPvP and pick a kit." : "")
                        : "§cNo bot spawned (max-bots " + svc.settings().maxBots + "?).");
            }
            case "despawn" -> {
                if (a.length < 2) return usage(s, "despawn <bot|all>");
                int n = 0;
                for (FakePlayer fp : bots(a[1])) {
                    if (svc.brain(fp.getPlayer()) == null) continue;
                    svc.detach(fp);
                    if (svc.plugin().getFakePlayerManager().delete(fp.getName(), "cpp_pvp")) n++;
                }
                s.sendMessage("§a" + n + " PvP bot(s) removed.");
                svc.saveState();
            }
            case "profile" -> {
                if (a.length < 3) return usage(s, "profile <bot|all> <profile>");
                if (!svc.settings().profiles.containsKey(a[2].toLowerCase(Locale.ROOT))) { s.sendMessage("§cNo profile " + a[2] + "."); return true; }
                int n = 0;
                for (FakePlayer fp : bots(a[1])) { BotBrain b = svc.brain(fp.getPlayer()); if (b != null) { b.profile = svc.settings().profile(a[2]); svc.pending().put(fp.getName().toLowerCase(Locale.ROOT), new String[]{b.profile.id, b.autoFill ? "1" : "0"}); n++; } }
                s.sendMessage("§a" + n + " bot(s) now " + a[2].toLowerCase(Locale.ROOT) + ".");
                svc.saveState();
            }
            case "fight" -> {
                if (a.length < 3) return usage(s, "fight <bot> <player|stop>");
                FakePlayer fp = svc.plugin().getFakePlayerManager().getByName(a[1]);
                BotBrain b = fp == null ? null : svc.brain(fp.getPlayer());
                if (b == null) { s.sendMessage("§c" + a[1] + " is not a PvP bot (/cpp pvp add " + a[1] + ")."); return true; }
                if (a[2].equalsIgnoreCase("stop")) { b.forcedTarget = null; s.sendMessage("§a" + fp.getName() + " picks its own fights again."); return true; }
                Player t = Bukkit.getPlayerExact(a[2]);
                if (t == null || t == fp.getPlayer()) { s.sendMessage("§cNo player " + a[2] + " online."); return true; }
                b.forcedTarget = t.getUniqueId();
                s.sendMessage("§a" + fp.getName() + " goes after " + t.getName() + " (anywhere in its world, any rules the server applies to the hit).");
            }
            case "list" -> {
                s.sendMessage("§6PvP bots: §f" + svc.brains().size() + "/" + svc.settings().maxBots + (svc.settings().autoFill ? " §7(auto-fill on)" : "")
                        + String.format(Locale.ROOT, " §7· %.0f µs/tick", svc.avgMicros()));
                KitPvpHook kp = svc.kitpvp();
                for (BotBrain b : svc.brains()) {
                    Player p = b.fp.getPlayer();
                    Player t = b.target() == null ? null : Bukkit.getPlayer(b.target());
                    String where = p == null ? "offline" : kp != null && kp.inGame(p) ? "KitPvP " + kp.arenaId(p) + ", kit " + (kp.kitId(p) == null ? "-" : kp.kitId(p)) : p.getWorld().getName();
                    s.sendMessage(" §e" + b.fp.getName() + " §7" + b.profile.id + (b.autoFill ? " (auto)" : "") + " · " + where
                            + (p != null ? " · " + Math.round(p.getHealth()) + " hp" : "") + (t != null ? " · fighting " + t.getName() : ""));
                }
                if (!svc.pending().isEmpty()) {
                    List<String> off = new ArrayList<>();
                    for (String n : svc.pending().keySet()) if (svc.plugin().getFakePlayerManager().getByName(n) == null) off.add(n);
                    if (!off.isEmpty()) s.sendMessage(" §7waiting to be online: " + String.join(", ", off));
                }
            }
            case "model" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("reload")) {
                    s.sendMessage("§7Reading " + svc.modelFile().getPath() + " ...");
                    Bukkit.getScheduler().runTaskAsynchronously(svc.plugin(), () -> svc.loadModel(true, s));
                } else s.sendMessage("§6Model: §f" + svc.modelStatus() + " §7(file " + svc.modelFile().getPath() + ")");
            }
            case "data" -> {
                CombatRecorder r = svc.recorder();
                if (a.length > 1 && a[1].equalsIgnoreCase("flush")) {
                    Bukkit.getScheduler().runTaskAsynchronously(svc.plugin(), () -> { r.flush(false); s.sendMessage("§aObservations written."); });
                    return true;
                }
                s.sendMessage("§6Learning data: §f" + (svc.settings().collect ? (r.paused() ? "§cpaused (disk limit)" : "§acollecting") : "§7off (learning.collect)")
                        + " §7· written " + r.written() + " · waiting " + r.pending() + " · dropped " + r.dropped() + " · folder " + r.folder().getPath());
            }
            case "reload" -> {
                svc.reload();
                s.sendMessage("§apvp-bots.yml reloaded (" + svc.settings().profiles.size() + " profiles). The model is re-checked within a second.");
            }
            default -> {
                s.sendMessage("§6/cpp pvp §7- PvP bots");
                s.sendMessage(" §eadd <bot|all> [profile] §7give bots a fighting brain");
                s.sendMessage(" §eremove <bot|all> §7take it away (bot stays)");
                s.sendMessage(" §espawn [count] [profile] §7new bots that join KitPvP and fight");
                s.sendMessage(" §edespawn <bot|all> §7remove PvP bots from the server");
                s.sendMessage(" §eprofile <bot|all> <profile> §7change skill: " + String.join(", ", svc.settings().profiles.keySet()));
                s.sendMessage(" §efight <bot> <player|stop> §7send a bot after someone");
                s.sendMessage(" §elist §7· §emodel [reload] §7· §edata [flush] §7· §ereload");
            }
        }
        return true;
    }

    private List<FakePlayer> bots(String which) {
        List<FakePlayer> out = new ArrayList<>();
        if (which.equalsIgnoreCase("all")) { out.addAll(svc.plugin().getFakePlayerManager().getActivePlayers()); return out; }
        FakePlayer fp = svc.plugin().getFakePlayerManager().getByName(which);
        if (fp != null) out.add(fp);
        return out;
    }

    private static int parse(String s, int def) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; } }

    private static boolean usage(CommandSender s, String u) { s.sendMessage("§cUsage: /cpp pvp " + u); return true; }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("add", "remove", "spawn", "despawn", "profile", "fight", "list", "model", "data", "reload"));
        else if (a.length == 2 && List.of("add", "remove", "despawn", "profile", "fight").contains(a[0].toLowerCase(Locale.ROOT))) {
            out.add("all");
            for (FakePlayer fp : svc.plugin().getFakePlayerManager().getActivePlayers()) out.add(fp.getName());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("model")) out.add("reload");
        else if (a.length == 2 && a[0].equalsIgnoreCase("data")) out.add("flush");
        else if (a.length == 3 && List.of("add", "profile").contains(a[0].toLowerCase(Locale.ROOT))) out.addAll(svc.settings().profiles.keySet());
        else if (a.length == 3 && a[0].equalsIgnoreCase("spawn")) out.addAll(svc.settings().profiles.keySet());
        else if (a.length == 3 && a[0].equalsIgnoreCase("fight")) { out.add("stop"); for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
