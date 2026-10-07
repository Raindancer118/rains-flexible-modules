package de.raindancer.modules.playerutils.service;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.playerutils.PlayerUtilsSettings;
import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.model.Verdict;
import de.raindancer.modules.playerutils.rules.TargetRule;
import de.raindancer.modules.playerutils.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * Who a command is pointed at: the sender, a name, a nickname, or a selector — and whether the sender may
 * point this action at each of them. Says why not, so nobody presses a button four more times.
 */
public final class Targeting implements IPlayerUtilsService {

    private final Server server;
    private final Messages messages;
    private final TargetRule rule;
    private volatile PlayerUtilsSettings settings;

    public Targeting(Server server, Messages messages, TargetRule rule, PlayerUtilsSettings settings) {
        this.server = server;
        this.messages = messages;
        this.rule = rule;
        this.settings = settings;
    }

    @Override
    public void settings(PlayerUtilsSettings fresh) {
        this.settings = fresh;
    }

    /**
     * The players {@code typed} means; the sender when nothing was typed. Empty — after saying why — when
     * nobody matches, a selector is not allowed, or too many match.
     */
    public List<Player> resolve(CommandSender sender, Optional<String> typed) {
        if (typed.isEmpty()) {
            if (sender instanceof Player self) {
                return List.of(self);
            }
            messages.send(sender, "playerutils.name-somebody");
            return List.of();
        }
        String text = typed.get();
        if (PlayerTargets.isSelector(text) && !sender.hasPermission(PermissionNodes.SELECTORS)) {
            messages.send(sender, "playerutils.no-selectors");
            return List.of();
        }
        PlayerLookup lookup = PlayerTargets.lookup(server, sender, text);
        switch (lookup.kind()) {
            case SELECTOR_REFUSED -> {
                messages.send(sender, "playerutils.no-selectors");
                return List.of();
            }
            case NONE -> {
                messages.send(sender, "playerutils.nobody", "player", text);
                return List.of();
            }
            default -> {
            }
        }
        if (lookup.isOfflineOnly()) {
            messages.send(sender, "playerutils.not-online", "player",
                    PlayerTargets.shownName(lookup.matches().getFirst()));
            return List.of();
        }
        List<Player> found = lookup.online();
        if (found.isEmpty()) {
            messages.send(sender, "playerutils.selector-nobody", "selector", text);
            return List.of();
        }
        int most = settings.maxSelectorTargets();
        if (found.size() > most) {
            messages.send(sender, "playerutils.too-many", "count", found.size(), "most", most);
            return List.of();
        }
        return found;
    }

    /** Whether {@code sender} may point {@code action} at {@code target}; says why not when not. */
    public boolean mayAct(CommandSender sender, Action action, Player target) {
        Verdict verdict = verdict(sender, action, target);
        if (!verdict.allowed()) {
            Object[] values = withPlayer(verdict.values(), target);
            messages.send(sender, verdict.key(), values);
        }
        return verdict.allowed();
    }

    /** The same question without saying anything — for greying a button. */
    public Verdict verdict(CommandSender sender, Action action, Player target) {
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        TargetRule.Asker asker = sender instanceof Player
                ? new TargetRule.Asker(false, sender::hasPermission) : TargetRule.Asker.CONSOLE;
        return rule.judge(action, asker, self, target::hasPermission);
    }

    private static Object[] withPlayer(Object[] values, Player target) {
        Object[] all = java.util.Arrays.copyOf(values, values.length + 2);
        all[values.length] = "player";
        all[values.length + 1] = PlayerTargets.shownName(target);
        return all;
    }

    @Override
    public String describe() {
        return "who a command is pointed at, and whether it may be";
    }
}
