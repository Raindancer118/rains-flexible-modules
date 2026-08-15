package de.raindancer.modules.essentials.moderation;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.service.PunishmentService;
import org.bukkit.Bukkit;

import java.time.Duration;
import java.util.UUID;

/**
 * The whole seam onto {@code moderation-module}, and the only place in this module that ever imports
 * one of its classes.
 *
 * <h2>Why this is one class rather than the module reaching in directly</h2>
 * Banning through moderation-module is genuinely optional — this module works exactly as it always
 * has, banning through Core's bare {@link Punishments} instead, when no moderation plugin is
 * installed at all. That only holds if nothing outside this package ever mentions a moderation-module
 * type: {@code PunishmentService} and {@code Sentence} are only resolvable when moderation-module's
 * classes are actually on the classpath, and the JVM only tries to resolve them when a class that
 * mentions them is first loaded. Confined to this package and reached only from behind the try/catch
 * below, that loading — and the {@code NoClassDefFoundError} it would otherwise throw — simply never
 * happens on a server with no moderation plugin.
 *
 * <p>The lookup goes through Bukkit's own {@code ServicesManager} rather than this reactor's
 * {@code ModuleContext#module(String)}, because that call only ever sees modules hosted in the
 * <em>same</em> plugin — two separate standalone jars, {@code RainsEssentials.jar} beside
 * {@code RainsModeration.jar}, are exactly the arrangement this has to work for, and
 * {@code ModerationModule} registers {@code PunishmentService} there for precisely this reason.
 */
public final class ModerationIntegration {

    private ModerationIntegration() {
    }

    /**
     * Bans for a day, through moderation-module's own {@code PunishmentService} when it is running —
     * the same path a moderator's own {@code /ban} takes, so the ban mirrors to the vanilla ban list,
     * kicks somebody already online, and is announced (or not) exactly by that module's own settings.
     *
     * <p>Falls back to Core's bare {@link Punishments} when moderation-module is not installed, or
     * whenever its classes cannot be reached, so a blocked nickname is still refused and still bans
     * rather than silently doing nothing — just without moderation's extra polish.
     *
     * <p>Caught broadly rather than narrowly on purpose: a server with no moderation plugin at all
     * has none of these classes on its classpath, and exactly what the JVM throws for that
     * ({@code NoClassDefFoundError}, not an {@code Exception}) is an implementation detail nothing
     * outside this method should have to know.
     */
    public static void banOneDay(Punishments corePunishments, UUID subject, String subjectName,
                                 String reason) {
        try {
            PunishmentService moderation = Bukkit.getServicesManager().load(PunishmentService.class);
            if (moderation != null) {
                moderation.punish(null, "the essentials module", subject, subjectName,
                        PunishmentKind.BAN, Sentence.of(Duration.ofDays(1)), reason);
                return;
            }
        } catch (Throwable notInstalled) {
            // Falls through to Core's own punishments below.
        }
        corePunishments.punish(subject, PunishmentKind.BAN, null, reason, Duration.ofDays(1));
    }
}
