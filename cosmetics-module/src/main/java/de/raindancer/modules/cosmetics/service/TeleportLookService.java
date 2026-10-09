package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.choose.ParticleCatalogue;
import de.raindancer.core.ui.choose.SoundCatalogue;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.core.ui.effect.ParticleShows;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.TravelLook;
import de.raindancer.core.world.teleport.TravelLooks;
import de.raindancer.core.world.teleport.TravelShow;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.model.ParticleDensity;
import de.raindancer.modules.cosmetics.model.ParticleSpeed;
import de.raindancer.modules.cosmetics.model.TeleportLookChoice;
import de.raindancer.modules.cosmetics.model.TeleportPart;
import de.raindancer.modules.cosmetics.model.Unlock;
import de.raindancer.modules.cosmetics.rules.TeleportLookRule;
import de.raindancer.modules.cosmetics.store.TeleportChoices;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's own teleport sounds and waiting particle, handed to Core's {@link TravelShow} so they
 * follow the player through every plugin's teleports.
 *
 * <p>Kept in memory per online player as well as in their data: Core asks on whichever thread sends
 * them, and with a teleport request that is the other player's — not a thread a player's data may be
 * read from.
 */
public final class TeleportLookService implements ICosmeticsService, TravelLooks {

    private final org.bukkit.plugin.Plugin plugin;
    private final Messages messages;
    private final TravelShow show;
    private final ParticleService particles;
    private final TeleportChoices store = new TeleportChoices();
    private final TeleportLookRule rule = new TeleportLookRule();
    private final Map<UUID, TeleportLookChoice> chosen = new ConcurrentHashMap<>();

    private volatile CosmeticsSettings settings;
    private volatile Entitlements entitlements = Entitlements.PERMISSIONS;

    public TeleportLookService(org.bukkit.plugin.Plugin plugin, Messages messages, TravelShow show,
                               ParticleService particles, CosmeticsSettings settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.show = show;
        this.particles = particles;
        settings(settings);
    }

    @Override
    public void settings(CosmeticsSettings fresh) {
        this.settings = fresh;
    }

    public void start() {
        show.looks(this);
    }

    public void stop() {
        show.release(this);
        chosen.clear();
    }

    @Override
    public TravelLook lookFor(UUID traveller) {
        if (!settings.teleportLooks()) {
            return TravelLook.SERVERS;
        }
        return chosen.getOrDefault(traveller, TeleportLookChoice.SERVERS).toLook();
    }

    /**
     * Read as they join, on their own thread, and checked again: a sound picked with a permission they no
     * longer have, or one the owner has since taken off the list, goes back to the server's — and they are
     * told. Called again for everybody online when the settings change.
     */
    public void load(Player who) {
        TeleportLookChoice read = store.read(who);
        // Switched off, nothing is played anyway, and the setting promises what they picked is kept.
        TeleportLookChoice stored = !settings.teleportLooks() ? read
                : read.keeping((part, value) -> judge(who, part, value).isAllowed());
        if (!stored.equals(read)) {
            store.write(who, stored);
            messages.send(who, "cosmetics.teleport.dropped");
        }
        if (stored.isServers()) {
            chosen.remove(who.getUniqueId());
        } else {
            chosen.put(who.getUniqueId(), stored);
        }
    }

    public void forget(UUID who) {
        chosen.remove(who);
    }

    public TeleportLookChoice current(Player who) {
        return chosen.getOrDefault(who.getUniqueId(), TeleportLookChoice.SERVERS);
    }

    public boolean mayUse(Player who) {
        return settings.teleportLooks() && entitlements.allowed(who, Unlock.TELEPORT, PermissionNodes.TELEPORT);
    }

    public boolean priced() {
        return entitlements.priced(Unlock.TELEPORT);
    }

    public String priceText() {
        return entitlements.priceText(Unlock.TELEPORT);
    }

    /** Hands in what decides who has bought what. Without it, permissions alone decide, as ever. */
    public void entitlements(Entitlements fresh) {
        this.entitlements = fresh == null ? Entitlements.PERMISSIONS : fresh;
    }

    public boolean mayPickAnySound(Player who) {
        return who.hasPermission(PermissionNodes.TELEPORT_ANY_SOUND);
    }

    /** The sounds {@code who} may pick from, for the chooser. */
    public SoundCatalogue sounds(Player who) {
        if (mayPickAnySound(who)) {
            return de.raindancer.core.ui.choose.SoundChooser.everythingOnThisServer();
        }
        return new SoundCatalogue(() -> settings.teleportSounds());
    }

    public ParticleCatalogue waitParticles() {
        return new ParticleCatalogue(particles::offered);
    }

    /**
     * Sets one part — a sound key or particle name, {@code none}/{@code off} for nothing, {@code default}
     * or null for the server's. Tells {@code who} either way.
     *
     * @return whether it was taken
     */
    public boolean choose(Player who, TeleportPart part, String typed) {
        String value = normalised(part, typed);
        Verdict verdict = judge(who, part, value);
        if (verdict.isRefused()) {
            messages.send(who, verdict.reason(), "value", verdict.detail(), "part", partName(part));
            return false;
        }
        TeleportLookChoice now = current(who).with(part, value);
        if (part == TeleportPart.WAIT && value != null && ParticleShows.takesColour(value)
                && now.waitColour() == null) {
            // Dust with no colour chosen would be drawn white; the worn particle starts red, so this does too.
            now = now.withWait(now.waitStyle().withColour(0xFF5555));
        }
        keep(who, now);
        if (value == null) {
            messages.send(who, "cosmetics.teleport.back-to-default", "part", partName(part));
        } else if (TeleportLookChoice.NONE.equals(value)) {
            messages.send(who, "cosmetics.teleport.set-none", "part", partName(part));
        } else {
            // Heard already: the chooser plays a sound on a left-click, before it is taken.
            messages.send(who, "cosmetics.teleport.set", "part", partName(part), "value", readable(part, value));
        }
        return true;
    }

    private void keep(Player who, TeleportLookChoice now) {
        store.write(who, now);
        if (now.isServers()) {
            chosen.remove(who.getUniqueId());
        } else {
            chosen.put(who.getUniqueId(), now);
        }
    }

    /** The waiting particle, as the particle page edits it — the same page as the worn one. */
    public ParticleSlot waitParticle() {
        return new WaitParticle();
    }

    private final class WaitParticle implements ParticleSlot {

        @Override
        public String heading() {
            return "While you wait to teleport";
        }

        @Override
        public ParticleChoice current(Player who) {
            return TeleportLookService.this.current(who).waitStyle();
        }

        @Override
        public boolean mayUse(Player who) {
            return TeleportLookService.this.mayUse(who);
        }

        @Override
        public String locked() {
            return entitlements.priced(Unlock.TELEPORT)
                    ? "Costs " + entitlements.priceText(Unlock.TELEPORT) + " — buy it under Unlocks in /cosmetics"
                    : "Needs " + PermissionNodes.TELEPORT;
        }

        @Override
        public ParticleCatalogue catalogue() {
            return waitParticles();
        }

        @Override
        public boolean wear(Player who, String particle) {
            return choose(who, TeleportPart.WAIT, particle);
        }

        private void restyle(Player who, java.util.function.UnaryOperator<ParticleChoice> change) {
            TeleportLookChoice now = TeleportLookService.this.current(who);
            if (!now.waitStyle().isNone()) {
                keep(who, now.withWait(change.apply(now.waitStyle())));
            }
        }

        @Override
        public boolean shape(Player who, ParticleShape shape) {
            restyle(who, style -> style.withShape(shape));
            return true;
        }

        @Override
        public boolean colour(Player who, int rgb) {
            restyle(who, style -> style.withColour(rgb & 0xFFFFFF));
            return true;
        }

        @Override
        public boolean colourTo(Player who, Integer rgb) {
            restyle(who, style -> style.withColourTo(rgb == null ? null : rgb & 0xFFFFFF));
            return true;
        }

        @Override
        public boolean mayUltra(Player who) {
            return who.hasPermission(PermissionNodes.PARTICLES_ULTRA);
        }

        @Override
        public ParticleDensity densityOf(Player who) {
            ParticleDensity density = current(who).density();
            return density == null ? ParticleDensity.NORMAL : density;
        }

        @Override
        public void density(Player who, ParticleDensity density) {
            restyle(who, style -> style.withDensity(rule.allowedDensity(density, mayUltra(who))));
        }

        @Override
        public boolean isCapped(ParticleDensity density) {
            // Core holds a waiting particle to twenty points; Ultra asks for thirty-two.
            return density == ParticleDensity.ULTRA;
        }

        @Override
        public boolean hasSpeed() {
            return false;
        }

        @Override
        public ParticleSpeed speedOf(Player who) {
            return ParticleSpeed.NORMAL;
        }

        @Override
        public void speed(Player who, ParticleSpeed speed) {
        }

        @Override
        public String takeOffTitle() {
            return "Back to the server's";
        }

        @Override
        public void takeOff(Player who) {
            choose(who, TeleportPart.WAIT, null);
        }

        @Override
        public void preview(Player who) {
            ParticleChoice style = current(who);
            if (style.isNone()) {
                messages.send(who, "cosmetics.particle.none-worn");
                return;
            }
            ParticleShows.preview(plugin, who, style.particle(), style.colour(), style.colourTo(),
                    Math.min(20, densityOf(who).count() * 2), style.shape(), 5, 1.0);
            messages.send(who, "cosmetics.preview.particle");
        }

        @Override
        public boolean isWorn() {
            return false;
        }
    }

    private Verdict judge(Player who, TeleportPart part, String value) {
        boolean enabled = settings.teleportLooks();
        boolean may = entitlements.allowed(who, Unlock.TELEPORT, PermissionNodes.TELEPORT);
        if (part.isSound()) {
            return rule.judgeSound(enabled, may, value, settings.teleportSounds(), mayPickAnySound(who),
                    value != null && isKnownSound(value));
        }
        Verdict access = rule.judgeAccess(enabled, may, value);
        if (access.isRefused() || value == null || TeleportLookChoice.NONE.equals(value)) {
            return access;
        }
        return particles.judge(who, value);
    }

    public String partName(TeleportPart part) {
        return messages.raw(switch (part) {
            case DEPART -> "cosmetics.teleport.part.depart";
            case ARRIVE -> "cosmetics.teleport.part.arrive";
            case WAIT -> "cosmetics.teleport.part.wait";
            case TICK -> "cosmetics.teleport.part.tick";
        });
    }

    /** What a choice reads as in a menu or a message. */
    public String readable(TeleportPart part, String value) {
        if (value == null) {
            return messages.raw("cosmetics.teleport.servers");
        }
        if (TeleportLookChoice.NONE.equals(value)) {
            return messages.raw("cosmetics.teleport.nothing");
        }
        return part.isSound() ? SoundCatalogue.readable(value) : ParticleCatalogue.readable(value);
    }

    private static String normalised(TeleportPart part, String typed) {
        if (typed == null) {
            return null;
        }
        String word = typed.trim();
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.equals("default") || lower.equals("reset") || lower.equals("server")) {
            return null;
        }
        if (lower.equals("none") || lower.equals("off") || lower.equals("nothing")) {
            return TeleportLookChoice.NONE;
        }
        if (part.isSound()) {
            return lower.startsWith("minecraft:") ? lower.substring("minecraft:".length()) : lower;
        }
        return word.toUpperCase(Locale.ROOT);
    }

    private static boolean isKnownSound(String key) {
        NamespacedKey namespaced = NamespacedKey.fromString(key.contains(":") ? key : "minecraft:" + key);
        return namespaced != null && Registry.SOUNDS.get(namespaced) != null;
    }

    /** For a test or a diagnostic: everybody whose choice is held. */
    public List<UUID> remembered() {
        return List.copyOf(chosen.keySet());
    }

    @Override
    public String describe() {
        return "players' own teleport sounds and waiting particles";
    }
}
