package net.minetake.mcinsight.introspect;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResourceGuideLogicTest {

    @Test
    void autoLanguageMatchesActiveAndFallsBackToEnglish() {
        assertTrue(ResourceGuideCollector.matchesLangCode("ja_jp", "ja_jp", false));
        assertTrue(ResourceGuideCollector.matchesLangCode("en_us", "ja_jp", false));
        assertFalse(ResourceGuideCollector.matchesLangCode("zh_cn", "ja_jp", false));
    }

    @Test
    void explicitLanguageAndAllBehavior() {
        assertTrue(ResourceGuideCollector.matchesLangCode("ja_jp", "ja_jp", true));
        assertFalse(ResourceGuideCollector.matchesLangCode("en_us", "ja_jp", true));

        assertTrue(ResourceGuideCollector.matchesLangCode("en_us", "all", true));
        assertTrue(ResourceGuideCollector.matchesLangCode("ja_jp", "all", true));
        assertTrue(ResourceGuideCollector.matchesLangCode("fr_fr", "all", false));
    }
}
