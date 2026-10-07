package de.raindancer.modules.essentials.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.model.Nickname;
import de.raindancer.modules.essentials.moderation.ModerationIntegration;
import de.raindancer.modules.essentials.rules.NicknameRule;
import de.raindancer.modules.essentials.store.EssentialsStore;
import de.raindancer.modules.essentials.store.NicknameBlocklist;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * What somebody is called, when it is not their own name.
 *
 * <h2>Why applying it does not touch Core</h2>
 * {@link Identities#chatName} and {@link Identities#nametag} already take the display name as a
 * parameter rather than owning one — this module supplies the nickname where it would otherwise
 * supply the real name, and Core's prefix, suffix and colour still wrap whichever name it is given.
 * Nothing new is needed there.
 *
 * <h2>Two places a nickname is kept, on purpose</h2>
 * This module's own store is what is <em>applied</em> to somebody on join. Core's {@link Nicknames}
 * directory is what every command on the server <em>looks names up in</em>, offline players included.
 * Every change writes both, and {@link #syncDirectory} brings the directory up to date when the module
 * starts, so nicknames set before the directory existed become resolvable too.
 *
 * <h2>Why a blocked attempt bans through moderation-module, when it is there</h2>
 * See {@link ModerationIntegration} — the same path a moderator's own {@code /ban} takes, so the ban
 * mirrors to the vanilla ban list, kicks somebody already online, and is announced by that module's
 * own settings, rather than a second, thinner idea of what banning does. Falls back to Core's bare
 * {@link Punishments} only when moderation-module is not installed at all.
 *
 * <p>Telling staff goes a plainer way, deliberately not through moderation-module's own report
 * queue: that queue has no public seam a module without a hard dependency on it could reach, so
 * this writes to the one thing every module already shares — the audit journal — and additionally
 * says so at once, in chat, to whoever holds {@link PermissionNodes#STAFF_NOTIFY}, the same way
 * moderation-module's own report filing tells staff.
 */
public final class NicknameService implements IEssentialsService {

    private final EssentialsStore store;
    private final NicknameBlocklist blocklist;
    private final Identities identities;
    private final Nicknames directory;
    private final Messages messages;
    private final Chat chat;
    private final Server server;
    private final Punishments punishments;
    private final Audit audit;
    private final BiConsumer<Player, Runnable> onOwnThread;
    private final NicknameRule rule = new NicknameRule();

    private volatile EssentialsSettings settings;

    public NicknameService(EssentialsStore store, NicknameBlocklist blocklist,
                           Identities identities, Nicknames directory, Messages messages, Chat chat,
                           Server server, Punishments punishments, Audit audit,
                           BiConsumer<Player, Runnable> onOwnThread, EssentialsSettings settings) {
        this.store = store;
        this.blocklist = blocklist;
        this.identities = identities;
        this.directory = directory;
        this.messages = messages;
        this.chat = chat;
        this.server = server;
        this.punishments = punishments;
        this.audit = audit;
        this.onOwnThread = onOwnThread;
        settings(settings);
    }

    @Override
    public void settings(EssentialsSettings fresh) {
        this.settings = fresh;
    }

    public boolean isEnabled() {
        return settings.nicknamesEnabled();
    }

    public Optional<String> nicknameOf(UUID who) {
        return store.nicknameOf(who);
    }

    /** What to show for this player right now — their nickname, or their own name. */
    public String displayNameOf(Player who) {
        return store.nicknameOf(who.getUniqueId()).orElse(who.getName());
    }

    /**
     * Somebody setting their own, with the real-name check already done by the caller.
     *
     * @param nameInUse whether a real player already answers to this
     * @return whether it took
     */
    public boolean set(Player who, String typed, boolean nameInUse) {
        return attempt(who, who, typed, nameInUse);
    }

    /**
     * Sets it for {@code target} — themselves or, for whoever holds the others node, anybody the
     * server knows, online or not. Works out for itself whether the name would impersonate somebody.
     *
     * @return whether it took
     */
    public boolean set(CommandSender actor, OfflinePlayer target, String typed) {
        if (!isSelf(actor, target) && !actor.hasPermission(PermissionNodes.NICK_OTHERS)) {
            messages.send(actor, "essentials.nick.not-others", "player", PlayerTargets.shownName(target));
            return false;
        }
        return attempt(actor, target, typed, impersonates(target, Nickname.of(typed).plain()));
    }

    /**
     * Whether this text is the real name of somebody <em>other than</em> {@code target}. Checked in the
     * spelling a command would type it too, so "Foo Bar" cannot stand in for a real "Foo_Bar".
     */
    boolean impersonates(OfflinePlayer target, String plain) {
        for (String spelling : List.of(plain, Nicknames.suggestion(plain))) {
            if (spelling.isBlank() || !PlayerTargets.isRealName(server, spelling)) {
                continue;
            }
            if (target.getName() == null || !spelling.equalsIgnoreCase(target.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean attempt(CommandSender actor, OfflinePlayer target, String typed, boolean nameInUse) {
        boolean self = isSelf(actor, target);
        Nickname nickname = Nickname.of(typed);
        // Staff who may skip the blocklist skip all of it, report and ban included — nobody is punished for
        // a name a moderator chose on purpose.
        NicknameRule.BlockMatch match = actor.hasPermission(PermissionNodes.NICK_BYPASS_BLOCKLIST)
                ? NicknameRule.BlockMatch.NONE : blocklist.matchOf(nickname.plain());
        boolean nickTaken = directory.ownersOf(nickname.plain()).stream()
                .anyMatch(owner -> !owner.equals(target.getUniqueId()));
        Verdict verdict = rule.judge(new NicknameRule.Request(nickname, settings.nicknameLimit(), nameInUse,
                match, nickTaken));
        if (verdict.isRefused()) {
            messages.send(actor, verdict.reason(), "detail", verdict.detail() == null ? "" : verdict.detail());
            // Only the person who typed their own name is flagged: an admin who tried it on somebody else
            // has not made the target do anything.
            if (match != NicknameRule.BlockMatch.NONE && self && actor instanceof Player who) {
                flag(who, nickname.plain(), match);
            }
            return false;
        }
        store.setNickname(target.getUniqueId(), nickname.raw());
        store.flush();
        directory.remember(target.getUniqueId(), nickname.plain());
        Player online = target instanceof Player here ? here : target.getPlayer();
        if (online != null) {
            onOwnThread.accept(online, () -> apply(online));
        }
        if (self) {
            messages.send(actor, "essentials.nick.set", "nickname", nickname.raw());
            return true;
        }
        String shown = PlayerTargets.shownName(target);
        messages.send(actor, "essentials.nick.set-other", "player", shown, "nickname", nickname.raw());
        if (online != null) {
            messages.send(online, "essentials.nick.set-by-staff", "nickname", nickname.raw());
        }
        audit(actor, target, "set somebody's nickname", nickname.plain());
        return true;
    }

    public void clear(Player who) {
        clear(who, who);
    }

    /**
     * Takes the nickname off — their own, or, with the others node, anybody's.
     *
     * @return whether there was one to take off
     */
    public boolean clear(CommandSender actor, OfflinePlayer target) {
        boolean self = isSelf(actor, target);
        if (!self && !actor.hasPermission(PermissionNodes.NICK_OTHERS)) {
            messages.send(actor, "essentials.nick.not-others", "player", PlayerTargets.shownName(target));
            return false;
        }
        Optional<String> previous = store.nicknameOf(target.getUniqueId());
        if (previous.isEmpty() && !self) {
            messages.send(actor, "essentials.nick.none-to-clear", "player", PlayerTargets.shownName(target));
            return false;
        }
        store.clearNickname(target.getUniqueId());
        store.flush();
        directory.clear(target.getUniqueId());
        Player online = target instanceof Player here ? here : target.getPlayer();
        if (online != null) {
            onOwnThread.accept(online, () -> apply(online));
        }
        if (self) {
            messages.send(actor, "essentials.nick.cleared");
            return previous.isPresent();
        }
        messages.send(actor, "essentials.nick.cleared-other", "player", PlayerTargets.shownName(target));
        if (online != null) {
            messages.send(online, "essentials.nick.cleared-by-staff");
        }
        audit(actor, target, "cleared somebody's nickname", previous.map(raw -> Nickname.of(raw).plain()).orElse(""));
        return true;
    }

    /**
     * Writes every nickname this module has stored into Core's directory, so the ones set before the
     * directory existed answer to commands too.
     *
     * <p>Deliberately never removes anything: the directory is shared, and an entry this module does not
     * know about may be somebody else's to keep.
     *
     * @return how many were handed over
     */
    public int syncDirectory() {
        int synced = 0;
        for (var entry : store.nicknames().entrySet()) {
            String plain = Nickname.of(entry.getValue()).plain();
            if (!plain.isBlank()) {
                directory.remember(entry.getKey(), plain);
                synced++;
            }
        }
        return synced;
    }

    private static boolean isSelf(CommandSender actor, OfflinePlayer target) {
        return actor instanceof Player who && who.getUniqueId().equals(target.getUniqueId());
    }

    private void audit(CommandSender actor, OfflinePlayer target, String action, String nickname) {
        AuditEntry.Builder entry = AuditEntry.of("essentials", action)
                .to(target.getUniqueId(), target.getName() == null ? target.getUniqueId().toString() : target.getName())
                .saying(nickname)
                .with("nickname", nickname);
        if (actor instanceof Player who) {
            entry.by(who.getUniqueId(), who.getName());
        }
        audit.record(entry);
    }

    /** Redraws how this player is shown — in the player list and in chat — from what is stored now. */
    public void apply(Player who) {
        String display = displayNameOf(who);
        who.playerListName(identities.nametag(who.getUniqueId(), display));
        who.displayName(identities.chatName(who.getUniqueId(), display));
        // The tablist and the nametag draw from Core, so Core is told — as plain text: a name is
        // painted by the player's name style, never by markup typed into /nick.
        identities.setNickname(who.getUniqueId(), settings.nicknameShownEverywhere()
                ? store.nicknameOf(who.getUniqueId()).map(raw -> Nickname.of(raw).plain()).orElse(null)
                : null);
    }

    /**
     * The consequences of a blocklisted attempt: always audited, always told to staff at once, and
     * — for the more severe tier — a one-day ban on top.
     *
     * <p>The ban is recorded before staff are told, so the chat line can say it already happened
     * rather than promising something that has not landed yet.
     */
    private void flag(Player who, String attempted, NicknameRule.BlockMatch match) {
        boolean banned = match == NicknameRule.BlockMatch.BANNED;
        if (banned) {
            ModerationIntegration.banOneDay(punishments, who.getUniqueId(), who.getName(),
                    "attempted a blocklisted nickname: " + attempted);
        }
        audit.record(AuditEntry.of("essentials", "attempted a blocklisted nickname")
                .by(who.getUniqueId(), who.getName())
                .saying(attempted)
                .with("nickname", attempted)
                .with("banned", String.valueOf(banned)));

        List<Player> staff = new ArrayList<>();
        for (Player online : server.getOnlinePlayers()) {
            if (online.hasPermission(PermissionNodes.STAFF_NOTIFY)) {
                staff.add(online);
            }
        }
        if (staff.isEmpty()) {
            return;
        }
        chat.broadcast(staff,
                messages.raw(banned ? "essentials.nick.blocked-report-banned"
                        : "essentials.nick.blocked-report"),
                Chat.arg("player", who.getName()), Chat.arg("attempted", attempted));
    }

    @Override
    public String describe() {
        return "what somebody is called, when it is not their own name";
    }
}
