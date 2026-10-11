package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.punishment.BlockedAttempts;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.LogQuery;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.service.LogSearchService;
import de.raindancer.modules.moderation.util.AttemptAlertSwitch;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /attempts [player:<name>] [type:join|chat|command|build] [from:…] [to:…] [day:…] [last:…] [words]} — what
 * punished players tried to do anyway, from Core's audit journal, newest first. {@code /attempts alerts on|off}
 * is whether the one typing it is told as it happens.
 */
public final class AttemptsCommand extends LogSearchCommand<BlockedAttempts.Attempt> {

    public AttemptsCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.ATTEMPTS);
    }

    @Override
    public String describe() {
        return "what punished players tried to do anyway — joining while banned, talking while muted";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length >= 1 && args[0].equalsIgnoreCase("alerts")) {
            alerts(sender, args);
            return;
        }
        run(sender, args);
    }

    private void alerts(CommandSender sender, String[] args) {
        ModerationServices moderation = services();
        if (!(sender instanceof Player player)) {
            moderation.messages().send(sender, "moderation.attempts.alerts-players-only");
            return;
        }
        AttemptAlertSwitch alerts = new AttemptAlertSwitch(moderation.plugin());
        if (args.length >= 2 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            alerts.set(player, args[1].equalsIgnoreCase("on"));
        }
        moderation.messages().send(player, alerts.isOn(player) ? "moderation.attempts.alerts-on"
                : "moderation.attempts.alerts-off");
    }

    @Override
    protected String label() {
        return "attempts";
    }

    @Override
    protected String prefix() {
        return "attempts";
    }

    @Override
    protected List<String> refused(LogQuery query) {
        return services().logSearch().unknownTypes(query);
    }

    @Override
    protected List<String> moreKeys() {
        return List.of("type:join", "type:chat", "type:command", "type:build", "alerts");
    }

    @Override
    protected LogSearchService.Page<BlockedAttempts.Attempt> search(LogQuery query, int pageSize) {
        return services().logSearch().attempts(query, pageSize);
    }

    @Override
    protected Component line(BlockedAttempts.Attempt attempt, LogQuery query) {
        return line(attempt, query, false);
    }

    /** An address hash is shown to operators and the console only: it links accounts, which is an admin's question. */
    @Override
    protected Component line(BlockedAttempts.Attempt attempt, LogQuery query, CommandSender reader) {
        boolean addresses = !(reader instanceof Player player) || player.isOp();
        return line(attempt, query, addresses);
    }

    private Component line(BlockedAttempts.Attempt attempt, LogQuery query, boolean addresses) {
        return services().chat().mm(services().messages().raw("moderation.attempts.line"),
                Chat.formatted("time", time(attempt.at().toEpochMilli(),
                        attempt.address() == null || !addresses ? "" : " · address #" + attempt.address())),
                Chat.formatted("player", name(attempt.name(), attempt.player(), query)),
                Chat.arg("kind", kindWord(services(), attempt.kind())),
                Chat.arg("punishment", punishmentWords(attempt)),
                Chat.arg("detail", attempt.detail()));
    }

    /** "join", "talk", "use a command", "build" — from messages.yml. */
    public static String kindWord(ModerationServices services, BlockedAttempts.Kind kind) {
        return services.messages().raw("moderation.attempts.kinds." + kind.word());
    }

    /** "banned: griefing", or the server's own list's words. */
    public static String punishmentWords(BlockedAttempts.Attempt attempt) {
        String what = attempt.punishment() == null ? "refused" : attempt.punishment().past();
        return attempt.reason().isBlank() ? what : what + ": " + attempt.reason();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("alerts")) {
            return List.of("on", "off");
        }
        if (args.length >= 1 && args[args.length - 1].toLowerCase().startsWith("type:")) {
            return new ArrayList<>(List.of("type:join", "type:chat", "type:command", "type:build"));
        }
        return super.suggest(source, args);
    }
}
