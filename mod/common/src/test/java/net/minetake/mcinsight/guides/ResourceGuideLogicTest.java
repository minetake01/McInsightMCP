package net.minetake.mcinsight.guides;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResourceGuideLogicTest {

    @Test
    void automaticLanguageMatchesActiveAndFallsBackToEnglish() {
        assertTrue(LangEntries.matchesLanguage("ja_jp", "ja_jp", false));
        assertTrue(LangEntries.matchesLanguage("en_us", "ja_jp", false));
        assertFalse(LangEntries.matchesLanguage("zh_cn", "ja_jp", false));
    }

    @Test
    void explicitLanguageDoesNotFallBackToEnglish() {
        assertTrue(LangEntries.matchesLanguage("ja_jp", "ja_jp", true));
        assertFalse(LangEntries.matchesLanguage("en_us", "ja_jp", true));
    }

    @Test
    void allMatchesEveryLanguage() {
        assertTrue(LangEntries.matchesLanguage("en_us", "all", true));
        assertTrue(LangEntries.matchesLanguage("ja_jp", "all", true));
        assertTrue(LangEntries.matchesLanguage("fr_fr", "all", false));
    }

    @Test
    void languageCodesAreComparedWithoutCase() {
        assertTrue(LangEntries.matchesLanguage("JA_JP", "ja_jp", true));
        assertTrue(LangEntries.matchesLanguage("en_us", "ALL", true));
    }
}
