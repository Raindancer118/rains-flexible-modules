package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.ui.choose.StyleGrants;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.identity.Nametags;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.Grants;
import de.raindancer.modules.cosmetics.model.Preset;
import de.raindancer.modules.cosmetics.model.Unlock;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.permissions.Permissible;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NameStyleGrantsTest {

    private final Preset sunset = new Preset("sunset", "Sunset", NameStyle.NONE.withColour(NamedTextColor.RED), false);
    private final NameStyleService names = new NameStyleService(mock(Identities.class), mock(Nametags.class),
            mock(Messages.class), () -> new Catalogue(List.of(), List.of(sunset)), CosmeticsSettings.DEFAULTS);
    private final Permissible who = mock(Permissible.class);

    private static Entitlements selling(Set<String> priced, Set<String> owned) {
        return new Entitlements() {
            @Override
            public boolean allowed(Permissible who, String key, String node) {
                return priced.contains(key) ? owned.contains(key) : who.hasPermission(node);
            }

            @Override
            public boolean priced(String key) {
                return priced.contains(key);
            }

            @Override
            public String priceText(String key) {
                return "40 Coins";
            }

            @Override
            public boolean anySold() {
                return true;
            }
        };
    }

    @Test
    @DisplayName("without a shop the permissions decide, exactly as before")
    void permissionsAlone() {
        when(who.hasPermission(PermissionNodes.NAME_GRADIENT)).thenReturn(true);

        Grants grants = names.grantsOf(who);

        assertThat(grants.gradient()).isTrue();
        assertThat(grants.colour()).isFalse();
        assertThat(grants.sold()).isEmpty();
    }

    @Test
    @DisplayName("a priced gradient is not granted by its node, only by owning it; the rest stay with the nodes")
    void pricedGradient() {
        when(who.hasPermission(PermissionNodes.NAME_GRADIENT)).thenReturn(true);
        when(who.hasPermission(PermissionNodes.NAME_COLOUR)).thenReturn(true);
        names.entitlements(selling(Set.of(Unlock.NAME_GRADIENT), Set.of()));

        Grants grants = names.grantsOf(who);

        assertThat(grants.gradient()).isFalse();
        assertThat(grants.colour()).isTrue();

        names.entitlements(selling(Set.of(Unlock.NAME_GRADIENT), Set.of(Unlock.NAME_GRADIENT)));
        assertThat(names.grantsOf(who).gradient()).isTrue();
    }

    @Test
    @DisplayName("a sold preset is marked sold, and held only once bought")
    void soldPreset() {
        names.entitlements(selling(Set.of(Unlock.preset("sunset")), Set.of()));
        assertThat(names.grantsOf(who).sold()).containsExactly("sunset");
        assertThat(names.grantsOf(who).presets()).isEmpty();
        assertThat(names.mayUse(who, sunset)).isFalse();

        names.entitlements(selling(Set.of(Unlock.preset("sunset")), Set.of(Unlock.preset("sunset"))));
        assertThat(names.mayUse(who, sunset)).isTrue();
    }

    @Test
    @DisplayName("the style editor greys a priced feature with its price, and a free one with its node")
    void editorWording() {
        names.entitlements(selling(Set.of(Unlock.NAME_GRADIENT), Set.of()));

        StyleGrants grants = names.styleGrantsOf(who);

        assertThat(grants.refuseAddingStop(NameStyle.NONE.withColour(NamedTextColor.RED), 8))
                .hasValueSatisfying(sentence -> assertThat(sentence).contains("Costs 40 Coins"));
        assertThat(grants.refuseDecoration(TextDecoration.BOLD))
                .hasValueSatisfying(sentence -> assertThat(sentence).contains(PermissionNodes.DECORATION_PREFIX));
    }
}
