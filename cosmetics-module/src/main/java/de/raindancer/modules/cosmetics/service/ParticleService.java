package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.choose.ParticleCatalogue;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.core.ui.effect.ParticleShows;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.model.ParticleDensity;
import de.raindancer.modules.cosmetics.model.ParticleSpeed;
import de.raindancer.modules.cosmetics.model.Unlock;
import de.raindancer.modules.cosmetics.rules.ParticleRule;
import de.raindancer.modules.cosmetics.store.ParticleChoices;
import de.raindancer.modules.cosmetics.store.WingReservations;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wearing a particle, and drawing everybody's on a timer.
 *
 * <p>The timer runs on the global scheduler only to hand each online player to their own thread; the
 * choice is read and drawn there, because on Folia a player and the people near them belong to that
 * region.
 */
public final class ParticleService implements ICosmeticsService, ParticleSlot {

    /** Whether a player sees other people's particles. Their own choice, on by default. */
    public static final PlayerSwitch SEES = new PlayerSwitch("rainscosmetics", "see-particles", true);

    /** How far away a particle is still drawn for somebody. */
    private static final double RANGE = 32;

    private final Plugin plugin;
    private final Server server;
    private final Vanish vanish;
    private final Messages messages;
    private final ParticleChoices choices = new ParticleChoices();
    /** Wings, worn on top of whatever particle somebody wears — a slot of their own. */
    private final ParticleChoices wingChoices = new ParticleChoices("wings");
    private final WingSlot wings = new WingSlot();
    private final ParticleRule rule = new ParticleRule();
    private final WingReservations reservations;
    private final AtomicLong ticks = new AtomicLong();

    private volatile CosmeticsSettings settings;
    private volatile Entitlements entitlements = Entitlements.PERMISSIONS;
    private ScheduledTask timer;

    public ParticleService(Plugin plugin, Server server, Vanish vanish, Messages messages,
                           CosmeticsSettings settings, WingReservations reservations) {
        this.reservations = reservations;
        this.plugin = plugin;
        this.server = server;
        this.vanish = vanish;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public synchronized void settings(CosmeticsSettings fresh) {
        // Read on every tick, so a changed pace takes hold without restarting the timer.
        this.settings = fresh;
    }

    // ------------------------------------------------------------------ the timer

    public synchronized void start() {
        stop();
        // Every tick: coloured wings are redrawn each tick (see ParticleRule.everyTick); the rest is
        // skipped in draw() until its own turn comes round.
        timer = Scheduling.globalTimer(plugin, 1, 1, task -> {
            long tick = ticks.incrementAndGet();
            for (Player player : server.getOnlinePlayers()) {
                Scheduling.entity(plugin, player, () -> draw(player, tick));
            }
        });
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void draw(Player wearer, long tick) {
        if (!wearer.isOnline()) {
            return;
        }
        CosmeticsSettings now = settings;
        if (!rule.shows(now.particlesEnabled(), vanish.isVanished(wearer.getUniqueId()),
                wearer.getGameMode() == GameMode.SPECTATOR,
                wearer.hasPotionEffect(PotionEffectType.INVISIBILITY), wearer.isDead())) {
            return;
        }
        draw(wearer, tick, choices.read(wearer), now, this);
        ParticleChoice wing = wingChoices.read(wearer);
        // Reserved by somebody else since they put them on: not drawn until they next join, when they come off.
        if (reservations.mayWear(wearer.getUniqueId(), wing)) {
            draw(wearer, tick, wing.isNone() ? wing : wing.withDensity(rule.wingDensity(wing.density())), now, wings);
        }
    }

    /** @param slot what it is worn as — which decides, among other things, whether Ultra is theirs */
    private void draw(Player wearer, long tick, ParticleChoice choice, CosmeticsSettings now, ParticleSlot slot) {
        if (choice.isNone() || now.blocked().contains(choice.particle())) {
            return;
        }
        boolean everyTick = rule.everyTick(choice.shape(), ParticleShows.takesColour(choice.particle()),
                choice.natural());
        if (!rule.drawsNow(tick, now.everyTicks(), everyTick)) {
            return;
        }
        // Wings drawn naturally show a third of their points each time, so a flame or a leaf — which
        // lingers — flickers over the wing instead of piling into a solid block.
        int share = choice.shape().isWings() && !everyTick ? 3 : 1;
        ParticleShows.around(wearer, choice.particle(), choice.colour(), choice.colourTo(),
                rule.count(rule.allowed(choice.density(), slot.mayUltra(wearer)), now.count(), now.maxCount()),
                choice.shape(),
                rule.frame(rule.animationTick(tick, now.everyTicks(), everyTick), choice.speed()),
                RANGE, viewer -> viewer.equals(wearer) || SEES.isOn(viewer), everyTick ? 1 : null, share);
    }

    // ------------------------------------------------------------------ choosing

    /** Hands in what decides who has bought what. Without it, permissions alone decide, as ever. */
    public void entitlements(Entitlements fresh) {
        this.entitlements = fresh == null ? Entitlements.PERMISSIONS : fresh;
    }

    private String lockedText() {
        return entitlements.priced(Unlock.PARTICLES)
                ? "Costs " + entitlements.priceText(Unlock.PARTICLES) + " — buy it under Unlocks in /cosmetics"
                : "Needs " + PermissionNodes.PARTICLES;
    }

    @Override
    public ParticleChoice current(Player who) {
        return choices.read(who);
    }

    @Override
    public boolean mayUse(Player who) {
        return settings.particlesEnabled() && entitlements.allowed(who, Unlock.PARTICLES, PermissionNodes.PARTICLES);
    }

    public Verdict judge(Player who, String particle) {
        return rule.judge(particle, mayUse(who), settings.blocked(), ParticleShows.canShow(particle));
    }

    /** Every vanilla particle a player could wear here, for the picker and tab completion. */
    public List<String> offered() {
        java.util.Set<String> blocked = settings.blocked();
        return Arrays.stream(Particle.values()).map(Enum::name)
                .filter(ParticleShows::canShow).filter(name -> !blocked.contains(name)).sorted().toList();
    }

    public boolean wear(Player who, String particle, boolean announce) {
        Verdict verdict = judge(who, particle);
        if (verdict.isRefused()) {
            messages.send(who, verdict.reason(), "detail", verdict.detail() == null ? "" : verdict.detail());
            return false;
        }
        ParticleChoice before = choices.read(who);
        ParticleChoice next = before.withParticle(particle);
        if (ParticleShows.takesColour(next.particle()) && next.colour() == null) {
            next = next.withColour(0xFF5555);
        }
        choices.write(who, next);
        if (announce) {
            messages.send(who, "cosmetics.particle.set", "particle", ParticleCatalogue.readable(next.particle()));
        }
        return true;
    }

    @Override
    public boolean shape(Player who, ParticleShape shape) {
        ParticleChoice choice = choices.read(who);
        if (!choice.isNone()) {
            choices.write(who, choice.withShape(shape));
        }
        return true;
    }

    /** Their density, or the one the server draws with when they have not chosen. */
    @Override
    public ParticleDensity densityOf(Player who) {
        ParticleDensity chosen = choices.read(who).density();
        if (chosen != null) {
            return chosen;
        }
        ParticleDensity closest = ParticleDensity.LIGHT;
        for (var density : ParticleDensity.values()) {
            if (density.count() <= settings.count()) {
                closest = density;
            }
        }
        return closest;
    }

    /** Their speed, normal when they have not chosen. */
    @Override
    public ParticleSpeed speedOf(Player who) {
        ParticleSpeed chosen = choices.read(who).speed();
        return chosen == null ? ParticleSpeed.NORMAL : chosen;
    }

    @Override
    public void speed(Player who, ParticleSpeed speed) {
        ParticleChoice choice = choices.read(who);
        if (!choice.isNone()) {
            choices.write(who, choice.withSpeed(speed));
        }
    }

    /** Whether this density would be drawn as asked, or held down by the server's ceiling. */
    @Override
    public boolean isCapped(ParticleDensity density) {
        return density.count() > settings.maxCount();
    }

    @Override
    public void density(Player who, ParticleDensity density) {
        ParticleChoice choice = choices.read(who);
        if (!choice.isNone()) {
            choices.write(who, choice.withDensity(rule.allowed(density, mayUltra(who))));
        }
    }

    @Override
    public boolean colour(Player who, int rgb) {
        ParticleChoice choice = choices.read(who);
        if (!choice.isNone()) {
            choices.write(who, choice.withColour(rgb & 0xFFFFFF));
        }
        return true;
    }

    public void takeOff(Player who, boolean announce) {
        choices.write(who, ParticleChoice.NONE);
        if (announce) {
            messages.send(who, "cosmetics.particle.cleared");
        }
    }

    /** Shows them their particle, in its shape, in front of them for a few seconds. */
    @Override
    public void preview(Player who) {
        ParticleChoice choice = choices.read(who);
        if (choice.isNone()) {
            messages.send(who, "cosmetics.particle.none-worn");
            return;
        }
        ParticleShows.preview(plugin, who, choice.particle(), choice.colour(), choice.colourTo(),
                rule.count(rule.allowed(choice.density(), mayUltra(who)), settings.count(), settings.maxCount()),
                choice.shape(), 5,
                choice.speed() == null ? 1.0 : choice.speed().factor());
        messages.send(who, "cosmetics.preview.particle");
    }

    // ------------------------------------------------------------------ as the particle page's slot

    @Override
    public String heading() {
        return "Your particles";
    }

    @Override
    public String locked() {
        return lockedText();
    }

    @Override
    public ParticleCatalogue catalogue() {
        return new ParticleCatalogue(this::offered);
    }

    @Override
    public boolean wear(Player who, String particle) {
        return wear(who, particle, false);
    }

    @Override
    public boolean hasSpeed() {
        return true;
    }

    @Override
    public boolean mayUltra(Player who) {
        return who.hasPermission(PermissionNodes.PARTICLES_ULTRA);
    }

    @Override
    public boolean colourTo(Player who, Integer rgb) {
        ParticleChoice choice = choices.read(who);
        if (!choice.isNone()) {
            choices.write(who, choice.withColourTo(rgb == null ? null : rgb & 0xFFFFFF));
        }
        return true;
    }

    @Override
    public String takeOffTitle() {
        return "Take it off";
    }

    @Override
    public void takeOff(Player who) {
        takeOff(who, false);
    }

    @Override
    public boolean isWorn() {
        return true;
    }

    /** Flips whether this player sees other people's particles. @return whether they now do */
    public boolean toggleSeeing(Player who) {
        return SEES.toggle(who);
    }

    public boolean sees(Player who) {
        return SEES.isOn(who);
    }

    /** On join: a particle they may no longer wear — permission gone, or blocked since — comes off. */
    public void revalidate(Player who) {
        ParticleRule.Worn worn = rule.split(choices.read(who), wingChoices.read(who));
        if (!worn.particle().equals(choices.read(who)) || !worn.wings().equals(wingChoices.read(who))) {
            choices.write(who, worn.particle());
            wingChoices.write(who, worn.wings());
        }
        if (!settings.particlesEnabled()) {
            return;
        }
        ParticleChoice choice = choices.read(who);
        if (!choice.isNone() && judge(who, choice.particle()).isRefused()) {
            choices.write(who, ParticleChoice.NONE);
            messages.send(who, "cosmetics.particle.dropped");
        }
        ParticleChoice wing = wingChoices.read(who);
        if (!wing.isNone() && judge(who, wing.particle()).isRefused()) {
            wingChoices.write(who, ParticleChoice.NONE);
            messages.send(who, "cosmetics.wings.dropped");
        } else if (!reservations.mayWear(who.getUniqueId(), wing)) {
            takeOffReserved(who, wing);
        }
    }

    private void takeOffReserved(Player who, ParticleChoice wing) {
        wingChoices.write(who, ParticleChoice.NONE);
        messages.send(who, "cosmetics.wings.reserved-off", "owner",
                reservations.holderOf(wing).map(WingReservations.Reservation::ownerName).orElse("somebody"));
    }

    // ------------------------------------------------------------------ reserving wings

    public WingReservations reservations() {
        return reservations;
    }

    /**
     * Keeps the wings {@code who} wears to them; anybody else wearing the same takes them off now.
     */
    public WingReservations.Outcome reserve(Player who) {
        ParticleChoice wing = wingChoices.read(who);
        WingReservations.Outcome outcome = reservations.reserve(who.getUniqueId(), who.getName(), wing);
        if (outcome == WingReservations.Outcome.RESERVED) {
            for (Player other : server.getOnlinePlayers()) {
                if (!other.equals(who)) {
                    Scheduling.entity(plugin, other, () -> {
                        ParticleChoice theirs = wingChoices.read(other);
                        if (!theirs.isNone() && !reservations.mayWear(other.getUniqueId(), theirs)) {
                            takeOffReserved(other, theirs);
                        }
                    });
                }
            }
        }
        return outcome;
    }

    /** Frees the wings {@code who} wears; staff may free anybody's. */
    public WingReservations.Outcome release(Player who) {
        return reservations.release(who.getUniqueId(), who.hasPermission(PermissionNodes.ADMIN),
                wingChoices.read(who));
    }

    /** False, with them told whose they are, when the wings would be somebody else's reserved combination. */
    private boolean mayPutOn(Player who, ParticleChoice next) {
        if (reservations.mayWear(who.getUniqueId(), next)) {
            return true;
        }
        messages.send(who, "cosmetics.wings.reserved", "owner",
                reservations.holderOf(next).map(WingReservations.Reservation::ownerName).orElse("somebody"));
        return false;
    }

    // ------------------------------------------------------------------ wings

    /** The wings, as the particle page edits them. */
    public ParticleSlot wings() {
        return wings;
    }

    /** Takes the wings off too — for clearing somebody's cosmetics. */
    public void takeOffWings(Player who) {
        wingChoices.write(who, ParticleChoice.NONE);
    }

    /**
     * The wings slot: the same page and the same rules as the worn particle, on keys of its own, offering the
     * kinds of wings for a shape and a choice between crisp points and the particle as Minecraft draws it.
     */
    private final class WingSlot implements ParticleSlot {

        @Override
        public String heading() {
            return "Your wings";
        }

        @Override
        public ParticleChoice current(Player who) {
            return wingChoices.read(who);
        }

        @Override
        public boolean mayUse(Player who) {
            return ParticleService.this.mayUse(who);
        }

        @Override
        public String locked() {
            return lockedText();
        }

        @Override
        public ParticleCatalogue catalogue() {
            return ParticleService.this.catalogue();
        }

        @Override
        public boolean wear(Player who, String particle) {
            Verdict verdict = judge(who, particle);
            if (verdict.isRefused()) {
                messages.send(who, verdict.reason(), "detail", verdict.detail() == null ? "" : verdict.detail());
                return false;
            }
            ParticleChoice before = wingChoices.read(who);
            ParticleChoice next = before.isNone()
                    ? new ParticleChoice(particle, de.raindancer.core.ui.effect.ParticleShape.WINGS, null)
                    : before.withParticle(particle);
            if (ParticleShows.takesColour(next.particle()) && next.colour() == null) {
                next = next.withColour(0xFFFFFF).withColourTo(0x8CCDF0);
            }
            if (!mayPutOn(who, next)) {
                return false;
            }
            wingChoices.write(who, next);
            return true;
        }

        /** @return false when the change would make them somebody else's reserved wings */
        private boolean change(Player who, java.util.function.UnaryOperator<ParticleChoice> how) {
            ParticleChoice now = wingChoices.read(who);
            if (now.isNone()) {
                return true;
            }
            ParticleChoice next = how.apply(now);
            if (!mayPutOn(who, next)) {
                return false;
            }
            wingChoices.write(who, next);
            return true;
        }

        @Override
        public boolean shape(Player who, ParticleShape shape) {
            return !shape.isWings() || change(who, choice -> choice.withShape(shape));
        }

        @Override
        public boolean colour(Player who, int rgb) {
            return change(who, choice -> choice.withColour(rgb & 0xFFFFFF));
        }

        @Override
        public boolean colourTo(Player who, Integer rgb) {
            return change(who, choice -> choice.withColourTo(rgb == null ? null : rgb & 0xFFFFFF));
        }

        /** Wings at Ultra for everybody: they are what it was made for, and should look their best on anyone. */
        @Override
        public boolean mayUltra(Player who) {
            return true;
        }

        @Override
        public ParticleDensity densityOf(Player who) {
            return rule.wingDensity(wingChoices.read(who).density());
        }

        @Override
        public void density(Player who, ParticleDensity density) {
            change(who, choice -> choice.withDensity(rule.allowed(density, mayUltra(who))));
        }

        @Override
        public boolean isCapped(ParticleDensity density) {
            return ParticleService.this.isCapped(density);
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
            return "Take them off";
        }

        @Override
        public void takeOff(Player who) {
            takeOffWings(who);
        }

        @Override
        public void preview(Player who) {
            ParticleChoice choice = wingChoices.read(who);
            if (choice.isNone()) {
                messages.send(who, "cosmetics.wings.none-worn");
                return;
            }
            ParticleShows.preview(plugin, who, choice.particle(), choice.colour(), choice.colourTo(),
                    rule.count(rule.wingDensity(choice.density()), settings.count(), settings.maxCount()),
                    choice.shape(), 5, 1.0);
            messages.send(who, "cosmetics.preview.particle");
        }

        @Override
        public boolean isWorn() {
            return false;
        }

        @Override
        public boolean wingsOnly() {
            return true;
        }

        @Override
        public boolean hasStyle() {
            return true;
        }

        @Override
        public void natural(Player who, boolean natural) {
            change(who, choice -> choice.withNatural(natural));
        }
    }

    @Override
    public String describe() {
        return "worn particles, and drawing them";
    }
}
