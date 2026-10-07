package de.raindancer.modules.cosmetics;

import de.raindancer.core.ui.choose.StyleGrants;
import de.raindancer.core.ui.choose.Swatch;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.Grants;
import de.raindancer.modules.cosmetics.model.PaletteColour;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Cosmetics' permissions and palette, as Core's style editor understands them. */
class StyleEditorMappingTest {

    private static final TextColor PINK = TextColor.fromHexString("#f38baa");

    @Test
    @DisplayName("every grant carries over, so the editor greys exactly what the rule would refuse")
    void grantsCarryOver() {
        Grants grants = new Grants(true, false, true, false, Set.of(TextDecoration.BOLD), Set.of());

        StyleGrants mapped = NameStyleService.styleGrants(grants);

        assertThat(mapped.colour()).isTrue();
        assertThat(mapped.gradient()).isFalse();
        assertThat(mapped.anyColour()).isTrue();
        assertThat(mapped.animated()).isFalse();
        assertThat(mapped.decorations()).containsExactly(TextDecoration.BOLD);
    }

    @Test
    @DisplayName("a refusal still names the node that would allow it")
    void refusalsNameTheNode() {
        StyleGrants mapped = NameStyleService.styleGrants(Grants.NOTHING);

        assertThat(mapped.refuseAddingStop(de.raindancer.core.ui.text.NameStyle.NONE, 8))
                .contains("Needs " + PermissionNodes.NAME_COLOUR);
        assertThat(mapped.refuseDecoration(TextDecoration.BOLD))
                .hasValueSatisfying(sentence -> assertThat(sentence).contains(PermissionNodes.DECORATION_PREFIX));
        assertThat(mapped.refuseTypedColour(de.raindancer.core.ui.text.NameStyle.NONE.withColour(PINK), 8))
                .isPresent();
        assertThat(mapped.refuseFlowing(de.raindancer.core.ui.text.NameStyle.NONE.withColour(PINK)))
                .contains("Needs two colours or more");
    }

    @Test
    @DisplayName("the palette becomes swatches with the same label, colour and icon, in order")
    void paletteBecomesSwatches() {
        Catalogue catalogue = new Catalogue(List.of(
                new PaletteColour("pink", PINK, Material.PINK_DYE),
                new PaletteColour("blue", TextColor.fromHexString("#3c44aa"), Material.BLUE_DYE)), List.of());

        assertThat(catalogue.swatches()).containsExactly(
                new Swatch("pink", PINK, Material.PINK_DYE),
                new Swatch("blue", TextColor.fromHexString("#3c44aa"), Material.BLUE_DYE));
    }
}
