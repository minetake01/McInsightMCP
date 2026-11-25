package net.minetake.mcinsight.guides;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minetake.mcinsight.McInsightConstants;

/**
 * The language-file half of the guide index: which file a query should read, and what is worth
 * reading out of it.
 *
 * <p>A lang file mixes player-facing documentation with UI labels and death messages. Keeping the
 * judgement of "is this documentation" here means search behaves the same for every source, and the
 * language-matching rule (active language plus English fallback, or an explicit language) is stated
 * once instead of being re-derived at each call site.
 */
public final class LangEntries {

    /** One key/value pair of a lang file, with the lowercase forms search compares against. */
    public record Entry(String key, String keyLower, String value, String valueLower) {}

    private static final String[] GUIDE_KEY_MARKERS = {
        ".ponder.", "tooltip", "guide", "jei.", "jei_", "book", "desc", "page",
        "lore", ".info", ".entry", ".manual", ".ritual", ".glyph", ".familiar",
        ".perk", "advancement", ".text", ".tip", ".help", ".hint"
    };

    private static final String[] UI_NOISE_PREFIXES = {
        "key.", "options.", "chat.", "commands.", "disconnect.", "gui.narrate",
        "menu.", "subtitles.", "death.attack.", "stat.", "sound.", "config.",
        "telemetry.", "multiplayer.", "selectworld.", "createworld.", "addserver.",
        "lanserver.", "realms.", "mco."
    };

    private LangEntries() {}

    /**
     * Whether a lang file may answer a search in {@code targetLanguage}.
     *
     * <p>An explicit language matches itself only; automatic matching additionally accepts
     * {@code en_us}, because a pack that ships English text is still answering a player whose own
     * language file is missing. {@code all} always matches.
     */
    public static boolean matchesLanguage(String entryLanguage, String targetLanguage, boolean explicit) {
        if (targetLanguage == null || entryLanguage == null) {
            return false;
        }
        String target = targetLanguage.trim().toLowerCase(Locale.ROOT);
        String entry = entryLanguage.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(target)) {
            return true;
        }
        if (target.equals(entry)) {
            return true;
        }
        return !explicit && "en_us".equals(entry);
    }

    /** Parses the documentation-like entries of a lang file; {@code includeAll} keeps UI labels too. */
    public static List<Entry> parse(String text, boolean includeAll) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (Exception e) {
            McInsightConstants.LOG.warn("Failed to parse lang JSON ({} chars): {}", text.length(), e.getMessage());
            return List.of();
        }
        if (!parsed.isJsonObject()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, JsonElement> raw : parsed.getAsJsonObject().entrySet()) {
            if (!raw.getValue().isJsonPrimitive()) {
                continue;
            }
            String key = raw.getKey();
            String value = raw.getValue().getAsString();
            String keyLower = key.toLowerCase(Locale.ROOT);
            if (!includeAll && !isDocumentation(keyLower, value)) {
                continue;
            }
            entries.add(new Entry(key, keyLower, value, value.toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(entries);
    }

    /**
     * Whether a key/value pair is documentation rather than interface chrome.
     *
     * <p>Keys carrying a known documentation marker always count. Otherwise length decides, with a
     * lower bar for CJK text because those scripts express the same sentence in fewer characters,
     * and known UI prefixes are excluded first.
     */
    static boolean isDocumentation(String keyLower, String value) {
        for (String marker : GUIDE_KEY_MARKERS) {
            if (keyLower.contains(marker)) {
                return true;
            }
        }
        int minLength = containsCjk(value) ? 10 : 28;
        if (value.length() < minLength) {
            return false;
        }
        for (String prefix : UI_NOISE_PREFIXES) {
            if (keyLower.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= '\u3040' && ch <= '\u30ff')
                || (ch >= '\u4e00' && ch <= '\u9fff')
                || (ch >= '\uac00' && ch <= '\ud7af')) {
                return true;
            }
        }
        return false;
    }

    /** The JSON shape of a language hit, used by search so every source answers in one shape. */
    public static JsonObject hit(GuideIndex.Entry guide, Entry entry) {
        JsonObject out = new JsonObject();
        out.addProperty("id", guide.id());
        out.addProperty("source", guide.source());
        out.addProperty("modId", guide.modId());
        out.addProperty("lang", guide.langCodeLower());
        out.addProperty("key", entry.key());
        out.addProperty("snippet", snippet(entry.value()));
        return out;
    }

    private static String snippet(String value) {
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }
}
