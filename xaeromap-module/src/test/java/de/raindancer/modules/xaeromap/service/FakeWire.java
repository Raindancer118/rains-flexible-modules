package de.raindancer.modules.xaeromap.service;

import de.raindancer.modules.xaeromap.model.OpacPackets;
import de.raindancer.modules.xaeromap.util.Wire;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every packet the module tried to send, kept where a test can read it.
 *
 * <p>This is the only way to check this module at all: what it does is emit bytes at a client that no
 * test can run, and the failure mode of every mistake in them is a client that draws nothing and says
 * nothing. Reading the packets is reading the behaviour.
 */
public final class FakeWire implements Wire {

    /** One thing that was sent. */
    public record Sent(UUID player, String channel, byte[] message) {

        public int packet() {
            return OpacPackets.indexOf(message);
        }
    }

    private final List<Sent> sent = new ArrayList<>();

    @Override
    public synchronized void send(Player player, String channel, byte[] message) {
        sent.add(new Sent(player.getUniqueId(), channel, message));
    }

    public synchronized List<Sent> all() {
        return List.copyOf(sent);
    }

    public synchronized List<Sent> to(UUID player) {
        return sent.stream().filter(one -> one.player().equals(player)).toList();
    }

    /** Every packet of one kind, in the order they went out. */
    public synchronized List<Sent> ofPacket(int index) {
        return sent.stream().filter(one -> one.packet() == index).toList();
    }

    public synchronized List<Integer> packetOrder() {
        return sent.stream().map(Sent::packet).toList();
    }

    public synchronized int count(int packet) {
        return ofPacket(packet).size();
    }

    public synchronized void clear() {
        sent.clear();
    }

    public synchronized boolean isEmpty() {
        return sent.isEmpty();
    }
}
