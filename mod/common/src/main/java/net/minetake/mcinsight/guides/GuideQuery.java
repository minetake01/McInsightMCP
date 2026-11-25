package net.minetake.mcinsight.guides;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Set;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;

/**
 * Answers the three guide endpoints: list the indexed documents, search inside them, read one page.
 *
 * <p>All three read the same {@link GuideIndex} and page through {@link Page}, so list and search
 * differ only in which documents match, never in how the answer is shaped. Ids are compared
 * lowercase everywhere, because list and search report the id exactly as the index found it and a
 * caller should be able to paste that id back into {@code page} unchanged in case.
 */
public final class GuideQuery {

    /** The guide systems this mod indexes; {@code all} is a filter value, not a source. */
    private static final Set<String> SOURCES = Set.of(
        "patchouli", "guideme", "ftbquests", "ponder", "oracle", "lang"
    );

    private static final int SNIPPET_RADIUS = 240;

    private GuideQuery() {}

    public static JsonObject list(RequestContext ctx, Params p) throws Exception {
        int limit = InsightEndpoint.GUIDES_LIST.limit(p);
        int offset = p.offset();
        String source = sourceFilter(p.string("source"));
        String modId = lower(p.string("mod"));

        GuideIndex index = GuideIndex.get(ctx);
        JsonArray rows = new JsonArray();
        int total = 0;
        for (GuideIndex.Entry entry : index.entries()) {
            if (!matchesSource(entry, source) || !matchesMod(entry, modId)) {
                continue;
            }
            if (total >= offset && rows.size() < limit) {
                rows.add(GuideIndex.describe(entry));
            }
            total++;
        }
        return Page.of("guides", rows, offset, total);
    }

    public static JsonObject search(RequestContext ctx, Params p) throws Exception {
        int limit = InsightEndpoint.GUIDES_SEARCH.limit(p);
        int offset = p.offset();
        String query = lower(p.required("q", InsightError.INVALID_QUERY));
        String source = sourceFilter(p.string("source"));
        String modId = lower(p.string("mod"));
        String language = p.string("lang");
        boolean explicitLanguage = language != null && !language.isBlank();
        boolean includeAllLang = p.bool("includeAllLang", false);

        GuideIndex index = GuideIndex.get(ctx);
        String targetLanguage = explicitLanguage ? language.trim().toLowerCase(Locale.ROOT) : index.language();

        JsonArray hits = new JsonArray();
        int total = 0;
        for (GuideIndex.Entry entry : index.entries()) {
            if (!matchesSource(entry, source) || !matchesMod(entry, modId)) {
                continue;
            }
            if (entry.isLang()) {
                if (!LangEntries.matchesLanguage(entry.langCodeLower(), targetLanguage, explicitLanguage)) {
                    continue;
                }
                for (LangEntries.Entry langEntry : index.langEntries(entry, includeAllLang)) {
                    if (!langEntry.keyLower().contains(query) && !langEntry.valueLower().contains(query)) {
                        continue;
                    }
                    if (total >= offset && hits.size() < limit) {
                        hits.add(LangEntries.hit(entry, langEntry));
                    }
                    total++;
                }
                continue;
            }

            String content = index.content(entry);
            if (content == null) {
                continue;
            }
            int at = index.lowerContent(entry, content).indexOf(query);
            boolean idHit = entry.idLower().contains(query);
            if (at < 0 && !idHit) {
                continue;
            }
            if (total >= offset && hits.size() < limit) {
                JsonObject hit = new JsonObject();
                hit.addProperty("id", entry.id());
                hit.addProperty("source", entry.source());
                hit.addProperty("modId", entry.modId());
                hit.addProperty("snippet", at >= 0 ? snippet(content, at) : entry.displayPath());
                hits.add(hit);
            }
            total++;
        }

        JsonObject out = Page.of("hits", hits, offset, total);
        out.addProperty("query", query);
        out.addProperty("lang", targetLanguage);
        return out;
    }

    public static JsonObject page(RequestContext ctx, Params p) throws Exception {
        String id = p.required("id", InsightError.INVALID_ID);
        GuideIndex index = GuideIndex.get(ctx);
        GuideIndex.Entry entry = index.find(id);
        if (entry == null) {
            throw new InsightException(InsightError.NOT_FOUND, "No guide page is indexed as '" + id + "'.");
        }
        String content = index.content(entry);
        JsonObject out = GuideIndex.describe(entry);
        out.addProperty("ok", true);
        out.addProperty("content", content != null ? content : "");
        return out;
    }

    private static boolean matchesSource(GuideIndex.Entry entry, String source) {
        return "all".equals(source) || entry.source().equals(source);
    }

    private static boolean matchesMod(GuideIndex.Entry entry, String modId) {
        return modId.isEmpty() || entry.modIdLower().equals(modId);
    }

    /** Normalizes {@code source} and rejects a value no indexed source could answer. */
    private static String sourceFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return "all";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(normalized) || SOURCES.contains(normalized)) {
            return normalized;
        }
        throw new InsightException(
            InsightError.INVALID_SOURCE,
            "Parameter 'source' must be one of all, " + String.join(", ", SOURCES.stream().sorted().toList())
                + ", got: " + raw + "."
        );
    }

    private static String snippet(String content, int index) {
        int start = Math.max(0, index - SNIPPET_RADIUS / 2);
        int end = Math.min(content.length(), index + SNIPPET_RADIUS);
        return content.substring(start, end).replaceAll("\\s+", " ").trim();
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
