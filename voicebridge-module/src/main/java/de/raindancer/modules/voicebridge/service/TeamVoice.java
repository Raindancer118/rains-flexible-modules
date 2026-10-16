package de.raindancer.modules.voicebridge.service;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.raindancer.core.social.voice.VoiceGroups;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Simple Voice Chat's side of RainsCore's {@link VoiceGroups}: a game asks for "the red team in a group of their
 * own" and gets a hidden, isolated group with a password nobody is told, so nobody else wanders in.
 */
public final class TeamVoice implements VoiceGroups.Provider, IVoiceBridgeService {

    private final Supplier<Optional<VoicechatServerApi>> api;
    private final Map<String, UUID> groups = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> members = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public TeamVoice(Supplier<Optional<VoicechatServerApi>> api) {
        this.api = api;
    }

    @Override
    public void settings(de.raindancer.modules.voicebridge.VoiceBridgeSettings settings) {
        // Nothing read from the settings: a game decides who is grouped, not the owner of the bridge.
    }

    @Override
    public synchronized void place(String key, String name, Collection<UUID> players) {
        Optional<VoicechatServerApi> live = api.get();
        if (live.isEmpty()) {
            return;
        }
        Group group = null;
        for (UUID player : players) {
            VoicechatConnection connection = live.get().getConnectionOf(player);
            if (connection == null || !connection.isInstalled()) {
                continue;
            }
            if (group == null) {
                group = groupFor(live.get(), key, name);
            }
            connection.setGroup(group);
            members.computeIfAbsent(key, nobody -> new HashSet<>()).add(player);
        }
    }

    @Override
    public synchronized void disband(String key) {
        UUID id = groups.remove(key);
        Set<UUID> in = members.remove(key);
        Optional<VoicechatServerApi> live = api.get();
        if (id == null || in == null || live.isEmpty()) {
            return;
        }
        for (UUID player : in) {
            VoicechatConnection connection = live.get().getConnectionOf(player);
            if (connection != null && connection.getGroup() != null && id.equals(connection.getGroup().getId())) {
                connection.setGroup(null);
            }
        }
    }

    private Group groupFor(VoicechatServerApi live, String key, String name) {
        UUID known = groups.get(key);
        if (known != null) {
            Group found = VoicechatGateway.groupById(live, known);
            if (found != null) {
                return found;
            }
        }
        Group made = live.groupBuilder().setName(name).setHidden(true).setPersistent(false)
                .setType(Group.Type.ISOLATED).setPassword(Long.toHexString(random.nextLong())).build();
        groups.put(key, made.getId());
        return made;
    }
}
