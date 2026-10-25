package com.scpl.util;

import com.scpl.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlsAndTextTest {

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<script>1</script>", "//evil.example/x",
            "/\\evil.example", "ftp://example.com/a", "mailto:a@b.co", "http://", "not a url", "/a\nb"})
    void dangerousOrMalformedLinksAreRefused(String url) {
        assertThatThrownBy(() -> Urls.requireWebUrl(url, "Link")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void tooLongLinksAreRefused() {
        assertThatThrownBy(() -> Urls.requireWebUrl("https://example.com/" + "a".repeat(600), "Link"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void goodLinksAreKept_blankBecomesNull() {
        assertThat(Urls.requireWebUrl("https://example.com/a?b=1", "Link")).isEqualTo("https://example.com/a?b=1");
        assertThat(Urls.requireWebUrl("  https://example.com  ", "Link")).isEqualTo("https://example.com");
        assertThat(Urls.requireWebUrl("HTTPS://EXAMPLE.COM", "Link")).isEqualTo("HTTPS://EXAMPLE.COM");
        assertThat(Urls.requireWebUrl("/images/logo.svg", "Link")).isEqualTo("/images/logo.svg");
        assertThat(Urls.requireWebUrl(null, "Link")).isNull();
        assertThat(Urls.requireWebUrl("   ", "Link")).isNull();
    }

    @Test
    void whatsappLinksNeedDigitsWithACountryCode() {
        assertThat(Urls.whatsapp("98220 12345", "91", "Hi there")).isEqualTo("https://wa.me/919822012345?text=Hi%20there");
        assertThat(Urls.whatsapp("09822012345", "91", "x")).startsWith("https://wa.me/919822012345");
        assertThat(Urls.whatsapp("+44 7700 900123", "91", "x")).startsWith("https://wa.me/447700900123");
        assertThat(Urls.whatsapp("12", "91", "x")).isNull();
        assertThat(Urls.whatsapp(null, "91", "x")).isNull();
    }

    @Test
    void encodingAndTelLinks() {
        assertThat(Urls.encode("a b&c")).isEqualTo("a%20b%26c");
        assertThat(Urls.encode(null)).isEmpty();
        assertThat(Urls.tel("+91 98220-12345")).isEqualTo("tel:+919822012345");
        assertThat(Urls.tel("abc")).isNull();
        assertThat(Urls.tel(null)).isNull();
    }

    @Test
    void textHelpers() {
        assertThat(Text.clean("  a  ")).isEqualTo("a");
        assertThat(Text.clean("   ")).isNull();
        assertThat(Text.clean(null)).isNull();
        assertThat(Text.slugify("Green Valley")).isEqualTo("green-valley");
        assertThat(Text.slugify("  Caf\u00e9  Soci\u00e9t\u00e9!! ")).isEqualTo("cafe-societe");
        assertThat(Text.slugify("")).isEqualTo("society");
        assertThat(Text.slugify(null)).isEqualTo("society");
        assertThat(Text.slugify("@@@")).isEqualTo("society");
    }
}