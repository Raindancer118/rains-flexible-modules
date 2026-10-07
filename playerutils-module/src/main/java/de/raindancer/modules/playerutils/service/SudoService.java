package de.raindancer.modules.playerutils.service;

import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.players.Outcome;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.playerutils.PlayerUtilsSettings;
import de.raindancer.modules.playerutils.rules.SudoRule;
import de.raindancer.modules.playerutils.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * Speaking or acting as somebody else. Always audited with the exact line, never pointed at an operator
 * without its own node, and never used to run what {@link SudoRule} keeps out of other people's mouths.
 */
public final class SudoService implements IPlayerUtilsService {

    private final Plugin plugin;
    private final Server server;
    private final RainsCore core;
    private final Messages messages;
    private final SudoRule rule;
    private volatile PlayerUtilsSettings settings;

    public SudoService(Plugin plugin, Server server, RainsCore core, Messages messages, SudoRule rule,
                       PlayerUtilsSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.core = core;
        this.messages = messages;
        this.rule = rule;
        this.settings = settings;
    }

    @Override
    public void settings(PlayerUtilsSettings fresh) {
        this.settings = fresh;
    }

    public Outcome run(CommandSender sender, Player target, String line) {
        String shown = PlayerTargets.shownName(target);
        if (!settings.sudoEnabled()) {
            messages.send(sender, "playerutils.sudo.switched-off");
            return Outcome.NOTHING_TO_DO;
        }
        if (target.isOp() && sender instanceof Player && !sender.hasPermission(PermissionNodes.SUDO_OPS)) {
            messages.send(sender, "playerutils.sudo.no-ops", "player", shown);
            return Outcome.NOTHING_TO_DO;
        }
        Optional<String> refusal = rule.refuse(line, settings.sudoBlocked());
        if (refusal.isPresent()) {
            messages.send(sender, "playerutils.sudo.blocked", "reason", refusal.get());
            return Outcome.NOTHING_TO_DO;
        }
        Optional<String> said = SudoRule.chatLine(line);
        String detail;
        if (said.isPresent()) {
            detail = "said: " + said.get();
            Scheduling.onOwner(plugin, target, () -> target.chat(said.get()));
            messages.send(sender, "playerutils.sudo.said", "player", shown, "line", said.get());
        } else {
            String command = SudoRule.commandLine(line);
            detail = "ran: /" + command;
            Scheduling.onOwner(plugin, target, () -> server.dispatchCommand(target, command));
            messages.send(sender, "playerutils.sudo.ran", "player", shown, "line", command);
        }
        AuditEntry.Builder entry = AuditEntry.of("playerutils", "used /sudo")
                .to(target.getUniqueId(), target.getName()).saying(detail);
        if (sender instanceof Player player) {
            entry.by(player.getUniqueId(), player.getName());
        } else {
            entry.by(null, "console");
        }
        core.audit().record(entry);
        return Outcome.DONE;
    }

    @Override
    public String describe() {
        return "speaking or acting as somebody else, audited";
    }
}
