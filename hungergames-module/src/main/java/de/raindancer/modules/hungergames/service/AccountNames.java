package de.raindancer.modules.hungergames.service;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Who an <em>account name</em> is, for the places that hand out a role by name — gamemaster — where a
 * nickname must not be able to stand in for somebody.
 *
 * <p>A nickname is how somebody is called, not who they are: one that reads like a name this server has not
 * seen yet would otherwise hand the role to its owner instead of the person meant. So a nickname, like a
 * selector, is refused here with what to type instead, and everything else is the real account (online or
 * not) or a name nobody here has seen, which gets the same derived UUID {@code /allow} gives it.
 *
 * @param id      the real UUID, when the server knows the account
 * @param refusal why the text cannot be used, or null
 */
public record AccountNames(UUID id, String refusal) {

    public static AccountNames of(Server server, String name) {
        if (PlayerTargets.isSelector(name)) {
            return new AccountNames(null, "selectors cannot be used here - name the account");
        }
        PlayerLookup found = PlayerTargets.lookup(server, null, name);
        if (found.kind() == PlayerLookup.Kind.NICKNAME && !found.isEmpty()) {
            String real = found.matches().getFirst().getName();
            return new AccountNames(null, name + " is a nickname - use the account name"
                    + (real == null ? "" : " " + real));
        }
        Optional<UUID> id = found.kind() == PlayerLookup.Kind.NAME
                ? found.single().map(OfflinePlayer::getUniqueId) : Optional.empty();
        return new AccountNames(id.orElse(null), null);
    }

    /** The real UUID if the account is known, else {@link #derivedId}. */
    public UUID idOrDerived(String name) {
        return id != null ? id : derivedId(name);
    }

    /**
     * A stable UUID for a name nobody has seen here: it survives a restart, and the real one replaces it
     * the moment that player joins.
     */
    public static UUID derivedId(String name) {
        return UUID.nameUUIDFromBytes(("hungergames:" + name.toLowerCase(Locale.ROOT))
                .getBytes(StandardCharsets.UTF_8));
    }
}
