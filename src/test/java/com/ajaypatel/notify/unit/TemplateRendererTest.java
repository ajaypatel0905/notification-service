package com.ajaypatel.notify.unit;

import com.ajaypatel.notify.common.error.ValidationException;
import com.ajaypatel.notify.template.TemplateRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRendererTest {
    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void substitutesFlatAndNestedVariables() {
        String out = renderer.render("Hi {{ user.name }}, your {{plan}} plan at {{company}} is ready",
                Map.of("user", Map.of("name", "Jane"), "plan", "Pro", "company", "Acme"));
        assertThat(out).isEqualTo("Hi Jane, your Pro plan at Acme is ready");
    }

    @Test
    void failsFastOnMissingVariables() {
        assertThatThrownBy(() -> renderer.render("{{a}} {{b.c}} {{a}}", Map.of("a", 1)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("[b.c]");
    }

    @Test
    void treatsNonMapIntermediateAsMissing() {
        assertThatThrownBy(() -> renderer.render("{{user.name}}", Map.of("user", "not-a-map")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void leavesTextWithoutPlaceholdersUntouchedAndHandlesNull() {
        assertThat(renderer.render("plain $text with {single} braces", Map.of())).isEqualTo("plain $text with {single} braces");
        assertThat(renderer.render(null, Map.of())).isNull();
    }

    @Test
    void valuesWithRegexSpecialCharactersAreInsertedLiterally() {
        assertThat(renderer.render("{{v}}", Map.of("v", "$1 \\ back"))).isEqualTo("$1 \\ back");
    }

    @Test
    void listsPlaceholdersInOrderWithoutDuplicates() {
        assertThat(renderer.placeholders("{{b}} {{a}} {{b}} {{c.d}}")).containsExactly("b", "a", "c.d");
        assertThat(renderer.placeholders(null)).isEqualTo(Set.of());
    }
}
