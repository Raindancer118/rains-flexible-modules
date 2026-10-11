package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.chatlog.ChatLog;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.LogQuery;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.service.LogSearchService;
import net.kyori.adventure.text.Component;

import java.util.function.Supplier;

/**
 * {@code /chatlog [player:<name>] [from:<time>] [to:<time>] [day:<day>] [last:<2h>] [words] ["a phrase"] [page:<n>]}
 * — searches public chat as Core recorded it, newest first, in Berlin time. Private messages are not recorded,
 * so they are not here. {@code /chathistory} is the chat module's "what you missed", for everybody.
 */
public final class ChatLogCommand extends LogSearchCommand<ChatLog.Line> {

    public ChatLogCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.CHATLOG);
    }

    @Override
    public String describe() {
        return "searches everything said in public chat by player, time and words";
    }

    @Override
    protected String label() {
        return "chatlog";
    }

    @Override
    protected String prefix() {
        return "chatlog";
    }

    @Override
    protected LogSearchService.Page<ChatLog.Line> search(LogQuery query, int pageSize) {
        return services().logSearch().chat(query, pageSize);
    }

    @Override
    protected Component line(ChatLog.Line line, LogQuery query) {
        String channel = ChatLog.PUBLIC.equals(line.channel()) ? "" : " · " + line.channel();
        return services().chat().mm(services().messages().raw("moderation.chatlog.line"),
                Chat.formatted("time", time(line.at(), channel)),
                Chat.formatted("player", name(line.name(), line.player(), query)),
                Chat.arg("text", line.text()));
    }
}
