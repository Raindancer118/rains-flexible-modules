package de.raindancer.modules.essentials.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.model.Nickname;
import de.raindancer.modules.essentials.rules.NicknameRule;
import de.raindancer.modules.essentials.store.EssentialsStore;
import org.bukkit.entity.Player;

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
 */
public final class NicknameService implements IEssentialsService {

    private final EssentialsStore store;
    private final Identities identities;
    private final Messages messages;
    private final NicknameRule rule = new NicknameRule();

    private volatile EssentialsSettings settings;

    public NicknameService(EssentialsStore store, Identities identities, Messages messages,
                           EssentialsSettings settings) {
        this.store = store;
        this.identities = identities;
        this.messages = messages;
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
        Verdict verdict = rule.judge(new NicknameRule.Request(nickname, settings.nicknameLimit(),
                nameInUse));
        if (verdict.isRefused()) {
            messages.send(who, verdict.reason(), "detail",
                    verdict.detail() == null ? "" : verdict.detail());
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

    @Override
    public String describe() {
        return "what somebody is called, when it is not their own name";
    }
}
