package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.chatlog.ChatLog;
import de.raindancer.core.moderation.punishment.BlockedAttempts;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.LogQuery;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs a {@link LogQuery} against Core's chat log or its blocked attempts and hands back one page.
 *
 * <p>Every method reads a database and blocks: call them off the server's threads. A player is found by
 * their id, by the name the server knows them by now, and by every name they have chatted under, so a
 * rename does not hide anybody's history.
 */
public final class LogSearchService implements IModerationService {

    /** The zone staff type and read times in. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /**
     * One page of results.
     *
     * @param nobody players as typed who matched nobody — nothing was searched when this is not empty
     */
    public record Page<T>(List<T> items, long total, int page, int pages, List<String> nobody) {
    }

    private final Supplier<ChatLog> chatLog;
    private final Supplier<BlockedAttempts> attempts;
    private final Function<String, Optional<UUID>> knownPlayer;

    /** @param knownPlayer who a name means today — a real name, a nickname — or empty */
    public LogSearchService(Supplier<ChatLog> chatLog, Supplier<BlockedAttempts> attempts,
                            Function<String, Optional<UUID>> knownPlayer) {
        this.chatLog = chatLog;
        this.attempts = attempts;
        this.knownPlayer = knownPlayer;
    }

    @Override
    public void settings(ModerationSettings settings) {
        // Nothing read from settings yet; the zone is the server's audience's, not an owner's choice so far.
    }

    public Page<ChatLog.Line> chat(LogQuery query, int pageSize) {
        ChatLog log = chatLog.get();
        List<String> nobody = new ArrayList<>();
        Set<UUID> players = players(query.players(), log, nobody);
        if (!nobody.isEmpty() || log == null) {
            return new Page<>(List.of(), 0, query.page(), 0, nobody);
        }
        ChatLog.Search search = ChatLog.Search.everything().by(players)
                .between(query.from() == null ? Long.MIN_VALUE : query.from().toEpochMilli(),
                        query.to() == null ? Long.MAX_VALUE : query.to().toEpochMilli())
                .containing(query.terms().toArray(String[]::new));
        long total = log.count(search);
        int size = Math.max(1, pageSize);
        List<ChatLog.Line> lines = log.search(search, size, (query.page() - 1) * size);
        return new Page<>(lines, total, query.page(), pagesOf(total, size), List.of());
    }

    public Page<BlockedAttempts.Attempt> attempts(LogQuery query, int pageSize) {
        BlockedAttempts kept = attempts.get();
        List<String> nobody = new ArrayList<>();
        Set<UUID> players = players(query.players(), chatLog.get(), nobody);
        if (!nobody.isEmpty() || kept == null) {
            return new Page<>(List.of(), 0, query.page(), 0, nobody);
        }
        Set<BlockedAttempts.Kind> kinds = EnumSet.noneOf(BlockedAttempts.Kind.class);
        query.types().forEach(type -> BlockedAttempts.Kind.of(type).ifPresent(kinds::add));
        List<String> terms = query.terms().stream().map(term -> term.toLowerCase(Locale.ROOT)).toList();
        List<BlockedAttempts.Attempt> all = kept.search(players, query.from(), query.to(), kinds,
                de.raindancer.core.moderation.audit.AuditSearch.MAX_LIMIT).stream()
                .filter(attempt -> {
                    String text = (attempt.detail() + " " + attempt.reason()).toLowerCase(Locale.ROOT);
                    return terms.stream().allMatch(text::contains);
                }).toList();
        int size = Math.max(1, pageSize);
        int from = Math.min(all.size(), (query.page() - 1) * size);
        return new Page<>(List.copyOf(all.subList(from, Math.min(all.size(), from + size))), all.size(),
                query.page(), pagesOf(all.size(), size), List.of());
    }

    /** Kinds of attempt typed that do not exist. */
    public List<String> unknownTypes(LogQuery query) {
        return query.types().stream().filter(type -> BlockedAttempts.Kind.of(type).isEmpty()).toList();
    }

    private Set<UUID> players(List<String> typed, ChatLog log, List<String> nobody) {
        Set<UUID> found = new LinkedHashSet<>();
        for (String name : typed) {
            Set<UUID> these = new LinkedHashSet<>();
            try {
                these.add(UUID.fromString(name));
            } catch (IllegalArgumentException notAnId) {
                knownPlayer.apply(name).ifPresent(these::add);
                if (log != null) {
                    these.addAll(log.playersNamed(name));
                }
            }
            if (these.isEmpty()) {
                nobody.add(name);
            }
            found.addAll(these);
        }
        return found;
    }

    private static int pagesOf(long total, int size) {
        return (int) Math.max(1, (total + size - 1) / size);
    }
}
