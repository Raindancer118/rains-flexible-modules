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
import de.raindancer.modules.cosmetics.rules.ParticleRule;
import de.raindancer.modules.cosmetics.store.ParticleChoices;
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
public final class ParticleService implements ICosmeticsService {

    /** Whether a player sees other people's particles. Their own choice, on by default. */
    public static final PlayerSwitch SEES = new PlayerSwitch("rainscosmetics", "see-particles", true);

    /** How far away a particle is still drawn for somebody. */
    private static final double RANGE = 32;

    private final Plugin plugin;
    private final Server server;
    private final Vanish vanish;
    private final Messages messages;
    private final ParticleChoices choices = new ParticleChoices();
    private final ParticleRule rule = new ParticleRule();
    private final AtomicLong ticks = new AtomicLong();

    private volatile CosmeticsSettings settings;
    private ScheduledTask timer;

    public ParticleService(Plugin plugin, Server server, Vanish vanish, Messages messages,
                           CosmeticsSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.vanish = vanish;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public synchronized void settings(CosmeticsSettings fresh) {
        boolean newPace = settings.everyTicks() != fresh.everyTicks();
        this.settings = fresh;
        if (newPace && timer != null) {
            start();
        }
    }

    // ------------------------------------------------------------------ the timer

    public synchronized void start() {
        stop();
        long every = settings.everyTicks();
        timer = Scheduling.globalTimer(plugin, every, every, task -> {
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
        ParticleChoice choice = choices.read(wearer);
        if (choice.isNone()) {
            return;
        }
        CosmeticsSettings now = settings;
        if (!rule.shows(now.particlesEnabled(), vanish.isVanished(wearer.getUniqueId()),
                wearer.getGameMode() == GameMode.SPECTATOR,
                wearer.hasPotionEffect(PotionEffectType.INVISIBILITY), wearer.isDead())
                || now.blocked().contains(choice.particle())) {
            return;
        }
        ParticleShows.around(wearer, choice.particle(), choice.colour(), now.count(), choice.shape(), tick,
                RANGE, viewer -> viewer.equals(wearer) || SEES.isOn(viewer));
    }

    // ------------------------------------------------------------------ choosing

    public ParticleChoice current(Player who) {
        return choices.read(who);
    }

    public boolean mayUse(Player who) {
        return settings.particlesEnabled() && who.hasPermission(PermissionNodes.PARTICLES);
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

    public void shape(Player who, ParticleShape shape) {
        ParticleChoice choice = choices.read(who);
        if (!choice.isNone()) {
            choices.write(who, choice.withShape(shape));
        }
    }

    public void colour(Player who, int rgb) {
        ParticleChoice choice = choices.read(who);
        if (!choice.isNone()) {
            choices.write(who, choice.withColour(rgb & 0xFFFFFF));
        }
    }

    public void takeOff(Player who, boolean announce) {
        choices.write(who, ParticleChoice.NONE);
        if (announce) {
            messages.send(who, "cosmetics.particle.cleared");
        }
    }

    /** Shows them their particle, in its shape, in front of them for a few seconds. */
    public void preview(Player who) {
        ParticleChoice choice = choices.read(who);
        if (choice.isNone()) {
            messages.send(who, "cosmetics.particle.none-worn");
            return;
        }
        ParticleShows.preview(plugin, who, choice.particle(), choice.colour(), Math.max(2, settings.count()),
                choice.shape(), 5);
        messages.send(who, "cosmetics.preview.particle");
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
        ParticleChoice choice = choices.read(who);
        if (choice.isNone() || !settings.particlesEnabled()) {
            return;
        }
        if (judge(who, choice.particle()).isRefused()) {
            choices.write(who, ParticleChoice.NONE);
            messages.send(who, "cosmetics.particle.dropped");
        }
    }

    @Override
    public String describe() {
        return "worn particles, and drawing them";
    }
}
