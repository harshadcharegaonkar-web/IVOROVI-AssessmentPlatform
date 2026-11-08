package com.ivorovi.questions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ivorovi.platform.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Content format and TeX guard: only the structured format is stored; HTML, scripts and dangerous TeX are refused. */
class ContentFormatTest {

    private static Map<String, Object> doc(Object... blocks) {
        return Map.of("format", ContentFormat.FORMAT, "blocks", List.of(blocks));
    }

    private static Map<String, Object> para(Object... runs) {
        return Map.of("type", "paragraph", "runs", List.of(runs));
    }

    @Test
    void htmlInTextIsStoredAsTextNeverAsMarkup() {
        ContentFormat.Parsed p = ContentFormat.parse(doc(para(Map.of("text", "<img src=x onerror=alert(1)><script>alert(1)</script>"))), "content", 20000, 10);
        // the text is kept verbatim as a text run; renderers create text nodes, so it is displayed, not executed
        assertThat(p.document().toString()).contains("<script>");
        assertThat(p.plainText()).isEqualTo("<img src=x onerror=alert(1)><script>alert(1)</script>");
    }

    @Test
    void unknownKeysAndBlockTypesAreRefused() {
        assertThatThrownBy(() -> ContentFormat.parse(Map.of("format", ContentFormat.FORMAT, "blocks", List.of(Map.of("type", "html", "html", "<b>x</b>"))), "c", 100, 1))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ContentFormat.parse(doc(Map.of("type", "paragraph", "runs", List.of(Map.of("text", "a")), "onclick", "x")), "c", 100, 1))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ContentFormat.parse(doc(para(Map.of("text", "a", "style", "x"))), "c", 100, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ContentFormat.parse(doc(para(Map.of("text", "a", "marks", List.of("link")))), "c", 100, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ContentFormat.parse(Map.of("format", "html", "blocks", List.of(para(Map.of("text", "a")))), "c", 100, 1))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void directionOverrideAndControlCharactersAreRefused() {
        assertThatThrownBy(() -> ContentFormat.parse(doc(para(Map.of("text", "abc\u202Edef"))), "c", 100, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ContentFormat.parse(doc(para(Map.of("text", "abc\u0000"))), "c", 100, 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void imagesNeedAltTextUnlessDecorative() {
        UUID a = UUID.randomUUID();
        assertThatThrownBy(() -> ContentFormat.parse(doc(Map.of("type", "image", "asset", a.toString())), "c", 100, 1)).isInstanceOf(ApiException.class);
        assertThat(ContentFormat.parse(doc(Map.of("type", "image", "asset", a.toString(), "decorative", true)), "c", 100, 1).images()).hasSize(1);
        assertThat(ContentFormat.parse(doc(Map.of("type", "image", "asset", a.toString(), "alt", "A right triangle")), "c", 100, 1).images().getFirst().alt())
                .isEqualTo("A right triangle");
        assertThatThrownBy(() -> ContentFormat.parse(doc(Map.of("type", "image", "asset", "javascript:alert(1)", "alt", "x")), "c", 100, 1))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ContentFormat.parse(doc(Map.of("type", "image", "asset", a.toString(), "alt", "x", "src", "https://evil")), "c", 100, 1))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void limitsOnLengthAndImages() {
        assertThatThrownBy(() -> ContentFormat.parse(doc(para(Map.of("text", "x".repeat(101)))), "c", 100, 1)).isInstanceOf(ApiException.class);
        UUID a = UUID.randomUUID();
        Map<String, Object> img = Map.of("type", "image", "asset", a.toString(), "decorative", true);
        assertThatThrownBy(() -> ContentFormat.parse(doc(img, img), "c", 100, 1)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\\frac{a}{b}", "\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}", "\\int_0^1 x^2\\,dx", "\\sum_{i=1}^{n} i^2",
            "\\lim_{x\\to 0}\\frac{\\sin x}{x}", "\\frac{d}{dx}e^{x}", "x_{i}^{2}", "\\sqrt[3]{\\frac{a^2+b^2}{c}}", "\\{ x \\mid x > 0 \\}"})
    void mathematicsIsAccepted(String tex) {
        assertThatCode(() -> TexGuard.check(tex, "m")).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\\href{javascript:alert(1)}{x}", "\\url{https://evil}", "\\includegraphics{x.png}", "\\htmlClass{x}{y}", "\\htmlData{a=b}{x}",
            "\\html@mathml{x}{y}", "\\def\\x{\\x\\x}\\x", "\\newcommand{\\a}{b}", "\\let\\a\\b", "\\gdef\\a{b}", "\\csname foo\\endcsname", "\\char65",
            "\\verb|x|", "\\input{/etc/passwd}", "\\write18{rm}", "{unbalanced", "unbalanced}", "", "\u0007"})
    void dangerousOrMalformedTexIsRefused(String tex) {
        assertThatThrownBy(() -> TexGuard.check(tex, "m")).isInstanceOf(ApiException.class);
    }

    @Test
    void texLengthIsBounded() {
        assertThatThrownBy(() -> TexGuard.check("x".repeat(TexGuard.MAX_LENGTH + 1), "m")).isInstanceOf(ApiException.class);
    }

    @Test
    void mathInsideContentIsCheckedToo() {
        assertThatThrownBy(() -> ContentFormat.parse(doc(para(Map.of("math", "\\href{javascript:alert(1)}{x}"))), "c", 1000, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ContentFormat.parse(doc(Map.of("type", "math", "tex", "\\url{x}")), "c", 1000, 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void sourceSyntaxParsesInlineAndDisplayMath() {
        Map<String, Object> d = ContentFormat.fromSource("Evaluate $\\int_0^1 x\\,dx$ in \\$.\n\n$$\\frac{1}{2}$$");
        ContentFormat.Parsed p = ContentFormat.parse(d, "c", 1000, 0);
        assertThat(p.document().toString()).contains("math=\\int_0^1 x\\,dx").contains("tex=\\frac{1}{2}").contains("in $.");
    }
}
