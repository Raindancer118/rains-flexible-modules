package de.raindancer.modules.essentials.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.model.Nickname;
import de.raindancer.modules.essentials.moderation.ModerationIntegration;
import de.raindancer.modules.essentials.rules.NicknameRule;
import de.raindancer.modules.essentials.store.EssentialsStore;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * What somebody is called, when it is not their own name.
 *
 * <h2>Why applying it does not touch Core</h2>
 * {@link Identities#chatName} and {@link Identities#nametag} already take the display name as a
 * parameter rather than owning one — this module supplies the nickname where it would otherwise
 * supply the real name, and Core's prefix, suffix and colour still wrap whichever name it is given.
 * Nothing new is needed there.
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
    private final Identities identities;
    private final Messages messages;
    private final Chat chat;
    private final Server server;
    private final Punishments punishments;
    private final Audit audit;
    private final NicknameRule rule = new NicknameRule();

    private volatile EssentialsSettings settings;

    public NicknameService(EssentialsStore store, Identities identities, Messages messages,
                           Chat chat, Server server, Punishments punishments, Audit audit,
                           EssentialsSettings settings) {
        this.store = store;
        this.identities = identities;
        this.messages = messages;
        this.chat = chat;
        this.server = server;
        this.punishments = punishments;
        this.audit = audit;
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
     * Sets it, or says why not.
     *
     * @param nameInUse whether a real player already answers to this — worked out by the caller,
     *                  which is the one thing about this decision that needs the server
     * @return whether it took
     */
    public boolean set(Player who, String typed, boolean nameInUse) {
        Nickname nickname = Nickname.of(typed);
        NicknameRule.BlockMatch match = matchOf(nickname.plain());
        Verdict verdict = rule.judge(
                new NicknameRule.Request(nickname, settings.nicknameLimit(), nameInUse, match));
        if (verdict.isRefused()) {
            messages.send(who, verdict.reason(), "detail",
                    verdict.detail() == null ? "" : verdict.detail());
            if (match != NicknameRule.BlockMatch.NONE) {
                flag(who, nickname.plain(), match);
            }
            return false;
        }
        store.setNickname(who.getUniqueId(), nickname.raw());
        store.flush();
        apply(who);
        messages.send(who, "essentials.nick.set", "nickname", nickname.raw());
        return true;
    }

    public void clear(Player who) {
        store.clearNickname(who.getUniqueId());
        store.flush();
        apply(who);
        messages.send(who, "essentials.nick.cleared");
    }

    /** Redraws how this player is shown — in the player list and in chat — from what is stored now. */
    public void apply(Player who) {
        String display = displayNameOf(who);
        who.playerListName(identities.nametag(who.getUniqueId(), display));
        who.displayName(identities.chatName(who.getUniqueId(), display));
    }

    /** Which blocklist, if any, this plain-text nickname matches. Case-insensitive, exact match. */
    private NicknameRule.BlockMatch matchOf(String plain) {
        String lowered = plain.toLowerCase(Locale.ROOT);
        if (settings.blockedBanned().contains(lowered)) {
            return NicknameRule.BlockMatch.BANNED;
        }
        if (settings.blockedReported().contains(lowered)) {
            return NicknameRule.BlockMatch.REPORTED;
        }
        return NicknameRule.BlockMatch.NONE;
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
