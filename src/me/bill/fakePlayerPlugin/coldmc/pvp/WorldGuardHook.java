package me.bill.fakePlayerPlugin.coldmc.pvp;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;

/** WorldGuard's pvp flag, for bots. Only this class names WorldGuard; it loads only if WorldGuard is enabled. */
final class WorldGuardHook {
    private final RegionQuery query;

    private WorldGuardHook() { query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery(); }

    static WorldGuardHook tryCreate() {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return null;
        try { return new WorldGuardHook(); } catch (Throwable t) { return null; }
    }

    /** False where a region denies pvp (unset counts as allowed, like WorldGuard itself). */
    boolean pvpAllowed(Location l) {
        try {
            // only an explicit deny counts: testState() would also read "unset" as not allowed
            return query.queryState(BukkitAdapter.adapt(l), (RegionAssociable) null, Flags.PVP)
                    != com.sk89q.worldguard.protection.flags.StateFlag.State.DENY;
        } catch (Throwable t) {
            return true;
        }
    }
}
