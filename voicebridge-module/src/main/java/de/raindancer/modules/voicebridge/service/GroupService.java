package de.raindancer.modules.voicebridge.service;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.raindancer.modules.voicebridge.VoiceBridgeSettings;
import de.raindancer.modules.voicebridge.rules.GroupJoinRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Simple Voice Chat's groups for somebody without the mod: list, join, leave, create and invite —
 * everything SVC's group screen and commands do, which both refuse a player whose client has no mod.
 *
 * <p>Every action answers with a message key, empty for success, so a command and a menu say the
 * same thing.
 */
public final class GroupService implements IVoiceBridgeService {

    /** Sends an invite somebody can accept with a click; the accept comes back as {@link #accept}. */
    public interface Inviter {
        void invite(UUID inviter, UUID target, Group group);
    }

    public record GroupView(UUID id, String name, boolean locked, String type, List<UUID> members) {
    }

    public static final long INVITE_LIFETIME_MILLIS = 5 * 60 * 1000L;

    private static final int LONGEST_NAME = 24;

    private record Invite(UUID target, UUID group, long expiresAt) {
    }

    private final Supplier<Optional<VoicechatServerApi>> api;
    private final Function<UUID, Optional<Long>> links;
    private final GroupJoinRule rule;
    private final Function<Group, String> passwordOf;
    private final Supplier<Iterable<UUID>> online;
    private final Inviter inviter;
    private final LongSupplier clock;
    private final List<Invite> invites = new ArrayList<>();

    public GroupService(Supplier<Optional<VoicechatServerApi>> api, Function<UUID, Optional<Long>> links,
                        GroupJoinRule rule, Function<Group, String> passwordOf, Supplier<Iterable<UUID>> online,
                        Inviter inviter, LongSupplier clock) {
        this.api = api;
        this.links = links;
        this.rule = rule;
        this.passwordOf = passwordOf;
        this.online = online;
        this.inviter = inviter;
        this.clock = clock;
    }

    @Override
    public void settings(VoiceBridgeSettings settings) {
        // Groups are Simple Voice Chat's; nothing about them is this module's to configure.
    }

    /** Linked to Discord and playing without the mod: the people SVC's own group tools refuse. */
    public boolean isBridged(UUID player) {
        if (links.apply(player).isEmpty()) {
            return false;
        }
        VoicechatConnection connection = connection(player);
        return connection != null && !connection.isInstalled();
    }

    public Optional<Group> groupOf(UUID player) {
        VoicechatConnection connection = connection(player);
        return Optional.ofNullable(connection == null ? null : connection.getGroup());
    }

    public List<GroupView> groups() {
        Optional<VoicechatServerApi> live = api.get();
        if (live.isEmpty()) {
            return List.of();
        }
        List<GroupView> views = new ArrayList<>();
        for (Group group : live.get().getGroups()) {
            if (group.isHidden()) {
                continue;
            }
            List<UUID> members = new ArrayList<>();
            for (UUID player : online.get()) {
                VoicechatConnection connection = live.get().getConnectionOf(player);
                Group theirs = connection == null ? null : connection.getGroup();
                if (theirs != null && theirs.getId().equals(group.getId())) {
                    members.add(player);
                }
            }
            views.add(new GroupView(group.getId(), group.getName(), group.hasPassword(),
                    group.getType() == null ? "normal" : typeName(group.getType()), members));
        }
        return views;
    }

    public String join(UUID player, String nameOrId, String password) {
        VoicechatConnection connection = connection(player);
        if (connection == null) {
            return "voicebridge.join.not-ready";
        }
        Group group;
        try {
            group = find(nameOrId);
        } catch (IllegalStateException twoOfThem) {
            return "voicebridge.groups.ambiguous";
        }
        if (group == null) {
            return "voicebridge.groups.no-such-group";
        }
        String typed = unquote(password);
        String actual = group.hasPassword() ? passwordOf.apply(group) : null;
        return switch (rule.judge(group.hasPassword(), actual, typed, false)) {
            case JOIN -> {
                connection.setGroup(group);
                yield "";
            }
            case NEEDS_PASSWORD -> "voicebridge.groups.needs-password";
            case WRONG_PASSWORD -> "voicebridge.groups.wrong-password";
            case CANNOT_CHECK -> "voicebridge.groups.cannot-check";
        };
    }

    public String leave(UUID player) {
        VoicechatConnection connection = connection(player);
        if (connection == null || connection.getGroup() == null) {
            return "voicebridge.groups.not-in-group";
        }
        connection.setGroup(null);
        return "";
    }

    public String create(UUID player, String name, String password, String type) {
        VoicechatConnection connection = connection(player);
        Optional<VoicechatServerApi> live = api.get();
        if (connection == null || live.isEmpty()) {
            return "voicebridge.join.not-ready";
        }
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > LONGEST_NAME || trimmed.chars().anyMatch(Character::isISOControl)) {
            return "voicebridge.groups.bad-name";
        }
        Group.Type kind = typeOf(type);
        if (kind == null) {
            return "voicebridge.groups.bad-type";
        }
        Group.Builder builder = live.get().groupBuilder().setName(trimmed).setType(kind);
        String secret = unquote(password);
        if (secret != null && !secret.isBlank()) {
            builder.setPassword(secret);
        }
        connection.setGroup(builder.build());
        return "";
    }

    public String invite(UUID from, UUID target) {
        Optional<Group> group = groupOf(from);
        if (group.isEmpty()) {
            return "voicebridge.groups.not-in-group";
        }
        synchronized (invites) {
            invites.removeIf(old -> old.target().equals(target) && old.group().equals(group.get().getId()));
            invites.add(new Invite(target, group.get().getId(), clock.getAsLong() + INVITE_LIFETIME_MILLIS));
        }
        inviter.invite(from, target, group.get());
        return "";
    }

    /**
     * An invite was clicked: in, without the password — the inviter was already inside. Only with an
     * invite actually sent to this player for this group, and only once.
     */
    public String accept(UUID player, UUID groupId) {
        if (!spendInvite(player, groupId)) {
            return "voicebridge.groups.no-invite";
        }
        VoicechatConnection connection = connection(player);
        Group group = api.get().map(live -> VoicechatGateway.groupById(live, groupId)).orElse(null);
        if (group == null) {
            return "voicebridge.groups.no-such-group";
        }
        if (connection == null) {
            return "voicebridge.join.not-ready";
        }
        connection.setGroup(group);
        return "";
    }

    private boolean spendInvite(UUID player, UUID groupId) {
        long now = clock.getAsLong();
        synchronized (invites) {
            invites.removeIf(invite -> now >= invite.expiresAt());
            return invites.removeIf(invite -> invite.target().equals(player) && invite.group().equals(groupId));
        }
    }

    private Group find(String nameOrId) {
        Optional<VoicechatServerApi> live = api.get();
        if (live.isEmpty() || nameOrId == null || nameOrId.isBlank()) {
            return null;
        }
        String wanted = unquote(nameOrId);
        try {
            Group byId = VoicechatGateway.groupById(live.get(), UUID.fromString(wanted));
            if (byId != null) {
                return byId;
            }
        } catch (IllegalArgumentException notAnId) {
            // a name, then
        }
        Group found = null;
        for (Group group : live.get().getGroups()) {
            if (!group.isHidden() && group.getName().equalsIgnoreCase(wanted)) {
                if (found != null) {
                    throw new IllegalStateException("two groups called " + wanted);
                }
                found = group;
            }
        }
        return found;
    }

    private VoicechatConnection connection(UUID player) {
        return api.get().map(live -> live.getConnectionOf(player)).orElse(null);
    }

    /** SVC's invite writes the password in double quotes; typed by hand it usually is not. */
    private static String unquote(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    static Group.Type typeOf(String name) {
        return switch (name == null ? "normal" : name.toLowerCase(Locale.ROOT)) {
            case "normal", "" -> Group.Type.NORMAL;
            case "open" -> Group.Type.OPEN;
            case "isolated" -> Group.Type.ISOLATED;
            default -> null;
        };
    }

    static String typeName(Group.Type type) {
        if (type == Group.Type.OPEN) {
            return "open";
        }
        return type == Group.Type.ISOLATED ? "isolated" : "normal";
    }
}
