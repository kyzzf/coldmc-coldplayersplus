package me.bill.fakePlayerPlugin.coldmc;

import org.bukkit.command.CommandSender;

import me.bill.fakePlayerPlugin.command.FppCommand;

/** /cpp alias [player] - the same as /ajoin (ColdMC, 2026-11-13). */
public final class AliasCommand implements FppCommand {
    private final AutoJoin autoJoin;

    public AliasCommand(AutoJoin autoJoin) { this.autoJoin = autoJoin; }

    @Override public String getName() { return "alias"; }
    @Override public String getUsage() { return "[player]"; }
    @Override public String getDescription() { return "Brings a player's alt (auto-join.yml) online now."; }
    @Override public String getPermission() { return "cpp.alias"; }
    @Override public boolean execute(CommandSender sender, String[] args) { return autoJoin.aliasCommand(sender, args); }
}
