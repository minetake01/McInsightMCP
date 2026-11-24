package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import net.minetake.mcinsight.server.PerfMetrics;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class ResourceGuideCollector {

    private static final int MAX_PAGE_CHARS = 131_072;
    private static final int MAX_LANG_CHARS = 8 * 1024 * 1024;
    private static final int MAX_CACHED_PAGES = 256;
    private static final int MAX_CACHED_LANG_FILES = 256;
    private static final String[] GUIDE_LANG_KEY_MARKERS = {
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

    private static final AtomicReference<GuideIndex> INDEX_REF = new AtomicReference<>();
    private static final Object INDEX_BUILD_LOCK = new Object();

    private ResourceGuideCollector() {}

    public static void invalidateCache() {
        INDEX_REF.set(null);
    }

    private enum ManagerKind {
        ASSET,
        DATA,
        FILE
    }

    private record GuideEntry(
        String id,
        String idLower,
        String source,
        String modId,
        String modIdLower,
        String displayPath,
        ManagerKind managerKind,
        ResourceLocation rl,
        Path fsPath,
        String langCodeLower
    ) {}

    public record LangEntry(String key, String keyLower, String value, String valueLower) {}

    private static <V> Map<String, V> newLruCache(int maxSize) {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxSize;
            }
        });
    }

    private static final class GuideIndex {
        final long resourceGeneration;
        final long dataGeneration;
        final String activeClientLang;
        final ResourceManager assetRm;
        final ResourceManager dataRm;
        final List<GuideEntry> entries;
        final Map<String, GuideEntry> byId;
        final Map<String, String> contentCache = newLruCache(MAX_CACHED_PAGES);
        final Map<String, String> contentLowerCache = newLruCache(MAX_CACHED_PAGES);
        final Map<String, List<LangEntry>> langCache = newLruCache(MAX_CACHED_LANG_FILES);
        final Map<String, List<LangEntry>> allLangCache = newLruCache(MAX_CACHED_LANG_FILES);

        GuideIndex(
            long resourceGeneration,
            long dataGeneration,
            String activeClientLang,
            ResourceManager assetRm,
            ResourceManager dataRm,
            List<GuideEntry> entries
        ) {
            this.resourceGeneration = resourceGeneration;
            this.dataGeneration = dataGeneration;
            this.activeClientLang = activeClientLang;
            this.assetRm = assetRm;
            this.dataRm = dataRm;
            this.entries = entries;
            Map<String, GuideEntry> map = new HashMap<>(entries.size() * 2);
            for (GuideEntry ge : entries) {
                map.put(ge.id(), ge);
            }
            this.byId = Collections.unmodifiableMap(map);
        }

        String getContent(GuideEntry entry) {
            boolean isLang = "lang".equals(entry.source());
            if (!isLang) {
                String cached = contentCache.get(entry.id());
                if (cached != null) {
                    return cached;
                }
            }
            String loaded = readEntryContent(assetRm, dataRm, entry, isLang ? MAX_LANG_CHARS : MAX_PAGE_CHARS);
            if (!isLang && loaded != null) {
                contentCache.put(entry.id(), loaded);
            }
            return loaded;
        }

        String getContentLower(GuideEntry entry, String content) {
            String cached = contentLowerCache.get(entry.id());
            if (cached != null) {
                return cached;
            }
            String lower = content.toLowerCase(Locale.ROOT);
            contentLowerCache.put(entry.id(), lower);
            return lower;
        }

        List<LangEntry> getLangEntries(GuideEntry ge, boolean includeAll) {
            Map<String, List<LangEntry>> targetCache = includeAll ? allLangCache : langCache;
            List<LangEntry> cached = targetCache.get(ge.id());
            if (cached != null) {
                return cached;
            }
            String content = getContent(ge);
            if (content == null) {
                return List.of();
            }
            List<LangEntry> parsed = parseLangEntries(content, includeAll);
            targetCache.put(ge.id(), parsed);
            return parsed;
        }
    }

    private record ManagerPair(ResourceManager assetRm, ResourceManager dataRm, String activeLang) {}

    private static GuideIndex getOrBuildIndex(MainThreadExecutor executor) throws Exception {
        long resGen = InsightCacheManager.getResourceGeneration();
        long dataGen = InsightCacheManager.getDataGeneration();
        GuideIndex existing = INDEX_REF.get();
        if (existing != null && existing.resourceGeneration == resGen && existing.dataGeneration == dataGen) {
            PerfMetrics.markCacheHit();
            return existing;
        }

        synchronized (INDEX_BUILD_LOCK) {
            resGen = InsightCacheManager.getResourceGeneration();
            dataGen = InsightCacheManager.getDataGeneration();
            existing = INDEX_REF.get();
            if (existing != null && existing.resourceGeneration == resGen && existing.dataGeneration == dataGen) {
                PerfMetrics.markCacheHit();
                return existing;
            }

            // Step 1: Tiny main-thread hop only to grab ResourceManager references & active language
            ManagerPair rms = executor.supplyOnGameThread(
                () -> {
                    String lang = Services.PLATFORM.isClientSide()
                        ? ClientStateAccessor.getLanguageCode()
                        : "en_us";
                    String normLang = (lang == null || lang.isBlank())
                        ? "en_us"
                        : lang.trim().toLowerCase(Locale.ROOT);
                    return new ManagerPair(
                        GameStateSupport.getAssetResourceManager(executor),
                        GameStateSupport.getDataResourceManager(executor),
                        normLang
                    );
                },
                10000
            );

            Path questsDir = Services.PLATFORM.getConfigDir().resolve("ftbquests").resolve("quests");
            boolean hasQuestsDir = Files.isDirectory(questsDir);
            if (rms == null || (rms.assetRm() == null && rms.dataRm() == null && !hasQuestsDir)) {
                return null;
            }

            // Step 2: Off-thread resource & FTB Quests directory indexing
            List<GuideEntry> entries = collectGuideEntries(rms.assetRm(), rms.dataRm(), questsDir);
            GuideIndex built = new GuideIndex(resGen, dataGen, rms.activeLang(), rms.assetRm(), rms.dataRm(), entries);
            INDEX_REF.set(built);
            return built;
        }
    }

    public static JsonObject listGuides(
        MainThreadExecutor executor,
        String sourceFilter,
        String modFilter,
        int limit,
        int offset
    ) throws Exception {
        String normSource = (sourceFilter == null || sourceFilter.isBlank())
            ? "all"
            : sourceFilter.trim().toLowerCase(Locale.ROOT);
        String normMod = modFilter == null ? "" : modFilter.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 500));
        int clampedOffset = Math.max(0, offset);

        GuideIndex index = getOrBuildIndex(executor);
        if (index == null) {
            return JsonUtil.errorObject("NO_RESOURCES", "ResourceManager is not available.");
        }

        JsonArray arr = new JsonArray();
        int totalMatched = 0;
        for (GuideEntry ge : index.entries) {
            if (!"all".equals(normSource) && !ge.source().equals(normSource)) continue;
            if (!normMod.isEmpty() && !ge.modIdLower().equals(normMod)) continue;
            if (totalMatched >= clampedOffset && arr.size() < clampedLimit) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", ge.id());
                obj.addProperty("source", ge.source());
                obj.addProperty("modId", ge.modId());
                obj.addProperty("path", ge.displayPath());
                arr.add(obj);
            }
            totalMatched++;
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("offset", clampedOffset);
        out.addProperty("total", totalMatched);
        out.addProperty("count", arr.size());
        out.addProperty("hasMore", totalMatched > clampedOffset + arr.size());
        out.add("guides", arr);
        return out;
    }

    public static JsonObject searchGuides(
        MainThreadExecutor executor,
        String query,
        String sourceFilter,
        String modFilter,
        String lang,
        boolean includeAllLang,
        int limit,
        int offset
    ) throws Exception {
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normQ.isEmpty()) {
            return JsonUtil.errorObject("INVALID_QUERY", "Query parameter 'q' is required.");
        }
        String normSource = (sourceFilter == null || sourceFilter.isBlank())
            ? "all"
            : sourceFilter.trim().toLowerCase(Locale.ROOT);
        String normMod = modFilter == null ? "" : modFilter.trim().toLowerCase(Locale.ROOT);
        boolean langExplicit = lang != null && !lang.isBlank();
        int clampedLimit = Math.max(1, Math.min(limit, 50));
        int clampedOffset = Math.max(0, offset);

        GuideIndex index = getOrBuildIndex(executor);
        if (index == null) {
            return JsonUtil.errorObject("NO_RESOURCES", "ResourceManager is not available.");
        }

        String targetLang = langExplicit ? lang.trim().toLowerCase(Locale.ROOT) : index.activeClientLang;

        JsonArray hits = new JsonArray();
        int totalMatched = 0;

        for (GuideEntry ge : index.entries) {
            if (!"all".equals(normSource) && !ge.source().equals(normSource)) {
                continue;
            }
            if (!normMod.isEmpty() && !ge.modIdLower().equals(normMod)) {
                continue;
            }
            if ("lang".equals(ge.source())) {
                if (!matchesLangCode(ge.langCodeLower(), targetLang, langExplicit)) {
                    continue;
                }
                List<LangEntry> langEntries = index.getLangEntries(ge, includeAllLang);
                for (LangEntry le : langEntries) {
                    if (le.valueLower().contains(normQ) || le.keyLower().contains(normQ)) {
                        if (totalMatched >= clampedOffset && hits.size() < clampedLimit) {
                            JsonObject hit = new JsonObject();
                            hit.addProperty("id", ge.id());
                            hit.addProperty("source", "lang");
                            hit.addProperty("modId", ge.modId());
                            hit.addProperty("lang", ge.langCodeLower());
                            hit.addProperty("key", le.key());
                            String v = le.value();
                            hit.addProperty("snippet", v.length() > 300 ? v.substring(0, 300) + "..." : v);
                            hits.add(hit);
                        }
                        totalMatched++;
                    }
                }
            } else {
                String content = index.getContent(ge);
                if (content == null) continue;
                String lower = index.getContentLower(ge, content);
                int idx = lower.indexOf(normQ);
                if (idx >= 0 || ge.idLower().contains(normQ)) {
                    if (totalMatched >= clampedOffset && hits.size() < clampedLimit) {
                        JsonObject hit = new JsonObject();
                        hit.addProperty("id", ge.id());
                        hit.addProperty("source", ge.source());
                        hit.addProperty("modId", ge.modId());
                        hit.addProperty("snippet", idx >= 0 ? extractSnippet(content, idx, 240) : ge.displayPath());
                        hits.add(hit);
                    }
                    totalMatched++;
                }
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("query", query);
        out.addProperty("lang", targetLang);
        out.addProperty("offset", clampedOffset);
        out.addProperty("total", totalMatched);
        out.addProperty("count", hits.size());
        out.addProperty("hasMore", totalMatched > clampedOffset + hits.size());
        out.add("hits", hits);
        return out;
    }

    public static boolean matchesLangCode(String entryLangLower, String targetLangLower, boolean langExplicit) {
        if ("all".equals(targetLangLower)) {
            return true;
        }
        if (targetLangLower.equals(entryLangLower)) {
            return true;
        }
        // When using the auto-detected client language, also include en_us as universal fallback
        return !langExplicit && "en_us".equals(entryLangLower);
    }

    public static JsonObject getGuidePage(MainThreadExecutor executor, String guideId) throws Exception {
        if (guideId == null || guideId.isBlank()) {
            return JsonUtil.errorObject("INVALID_ID", "Guide 'id' is required.");
        }
        GuideIndex index = getOrBuildIndex(executor);
        if (index == null) {
            return JsonUtil.errorObject("NO_RESOURCES", "ResourceManager is not available.");
        }

        GuideEntry ge = index.byId.get(guideId);
        if (ge == null) {
            return JsonUtil.errorObject("NOT_FOUND", "Guide page not found: " + guideId);
        }

        String content = index.getContent(ge);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("id", ge.id());
        out.addProperty("source", ge.source());
        out.addProperty("modId", ge.modId());
        out.addProperty("path", ge.displayPath());
        out.addProperty("content", content != null ? content : "");
        return out;
    }

    private static List<GuideEntry> collectGuideEntries(
        ResourceManager assetRm,
        ResourceManager dataRm,
        Path questsDir
    ) {
        // Place structured guide sources first so large lang files cannot starve guidebooks
        Map<String, GuideEntry> guidesMap = new LinkedHashMap<>();
        Map<String, GuideEntry> langMap = new LinkedHashMap<>();

        if (assetRm != null) {
            scanPrefix(assetRm, ManagerKind.ASSET, "patchouli_books", "patchouli", guidesMap);
            scanPrefix(assetRm, ManagerKind.ASSET, "ae2guide", "guideme", guidesMap);
            scanPrefix(assetRm, ManagerKind.ASSET, "guides", "guideme", guidesMap);
            scanPrefix(assetRm, ManagerKind.ASSET, "books", "oracle", guidesMap);
            scanPrefix(assetRm, ManagerKind.ASSET, "ponder", "ponder", guidesMap);

            assetRm.listResources("lang", path -> path.getPath().endsWith(".json")).forEach((rl, res) -> {
                String p = rl.getPath().toLowerCase(Locale.ROOT);
                String langCode = "";
                if (p.startsWith("lang/") && p.endsWith(".json") && p.length() > 10) {
                    langCode = p.substring(5, p.length() - 5);
                }
                String id = "lang:" + rl;
                langMap.putIfAbsent(id, new GuideEntry(
                    id,
                    id.toLowerCase(Locale.ROOT),
                    "lang",
                    rl.getNamespace(),
                    rl.getNamespace().toLowerCase(Locale.ROOT),
                    rl.toString(),
                    ManagerKind.ASSET,
                    rl,
                    null,
                    langCode
                ));
            });
        }

        if (dataRm != null) {
            scanPrefix(dataRm, ManagerKind.DATA, "patchouli_books", "patchouli", guidesMap);
            scanPrefix(dataRm, ManagerKind.DATA, "ae2guide", "guideme", guidesMap);
            scanPrefix(dataRm, ManagerKind.DATA, "guides", "guideme", guidesMap);
            scanPrefix(dataRm, ManagerKind.DATA, "books", "oracle", guidesMap);
            scanPrefix(dataRm, ManagerKind.DATA, "ponder", "ponder", guidesMap);
        }

        if (questsDir != null && Files.isDirectory(questsDir)) {
            try (Stream<Path> walk = Files.walk(questsDir, 10)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".snbt"))
                    .forEach(p -> {
                        String rel = questsDir.relativize(p).toString().replace('\\', '/');
                        String id = "ftbquests:" + rel;
                        guidesMap.putIfAbsent(id, new GuideEntry(
                            id,
                            id.toLowerCase(Locale.ROOT),
                            "ftbquests",
                            "ftbquests",
                            "ftbquests",
                            rel,
                            ManagerKind.FILE,
                            null,
                            p,
                            null
                        ));
                    });
            } catch (Exception e) {
                McInsightConstants.LOG.warn("Failed scanning FTB Quests directory {}: {}", questsDir, e.getMessage());
            }
        }

        List<GuideEntry> ordered = new ArrayList<>(guidesMap.size() + langMap.size());
        ordered.addAll(guidesMap.values());
        ordered.addAll(langMap.values());
        return List.copyOf(ordered);
    }

    private static void scanPrefix(
        ResourceManager rm,
        ManagerKind kind,
        String prefix,
        String sourceTag,
        Map<String, GuideEntry> out
    ) {
        rm.listResources(prefix, path -> {
            String p = path.getPath().toLowerCase(Locale.ROOT);
            return p.endsWith(".json") || p.endsWith(".md") || p.endsWith(".txt");
        }).forEach((rl, res) -> {
            String id = kind == ManagerKind.DATA ? (sourceTag + ":data/" + rl) : (sourceTag + ":" + rl);
            String displayPath = kind == ManagerKind.DATA ? ("data/" + rl) : rl.toString();
            out.putIfAbsent(id, new GuideEntry(
                id,
                id.toLowerCase(Locale.ROOT),
                sourceTag,
                rl.getNamespace(),
                rl.getNamespace().toLowerCase(Locale.ROOT),
                displayPath,
                kind,
                rl,
                null,
                null
            ));
        });
    }

    private static String readCharsBounded(BufferedReader reader, int maxChars, String label) throws Exception {
        StringBuilder sb = new StringBuilder(4096);
        char[] buf = new char[8192];
        int remaining = maxChars;
        while (remaining > 0) {
            int n = reader.read(buf, 0, Math.min(buf.length, remaining));
            if (n <= 0) break;
            sb.append(buf, 0, n);
            remaining -= n;
        }
        if (remaining == 0 && reader.read() != -1) {
            McInsightConstants.LOG.warn("Guide/lang resource '{}' exceeded {} char limit and was truncated.", label, maxChars);
        }
        return sb.toString();
    }

    private static String readEntryContent(
        ResourceManager assetRm,
        ResourceManager dataRm,
        GuideEntry entry,
        int maxChars
    ) {
        try {
            if (entry.managerKind() == ManagerKind.FILE && entry.fsPath() != null) {
                try (BufferedReader reader = Files.newBufferedReader(entry.fsPath(), StandardCharsets.UTF_8)) {
                    return readCharsBounded(reader, maxChars, entry.id());
                }
            }
            ResourceManager targetRm = entry.managerKind() == ManagerKind.DATA ? dataRm : assetRm;
            if (targetRm != null && entry.rl() != null) {
                Optional<Resource> opt = targetRm.getResource(entry.rl());
                if (opt.isEmpty()) return null;
                Resource res = opt.get();
                try (InputStream is = res.open();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    return readCharsBounded(reader, maxChars, entry.id());
                }
            }
        } catch (Exception e) {
            McInsightConstants.LOG.warn("Failed reading guide entry '{}': {}", entry.id(), e.getMessage());
        }
        return null;
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

    private static boolean isUsefulGuideLangEntry(String keyLower, String value) {
        for (String marker : GUIDE_LANG_KEY_MARKERS) {
            if (keyLower.contains(marker)) {
                return true;
            }
        }
        int minLen = containsCjk(value) ? 10 : 28;
        if (value.length() >= minLen) {
            for (String prefix : UI_NOISE_PREFIXES) {
                if (keyLower.startsWith(prefix)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public static List<LangEntry> parseLangEntries(String content, boolean includeAll) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) return List.of();
            JsonObject obj = parsed.getAsJsonObject();
            List<LangEntry> list = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (!e.getValue().isJsonPrimitive()) continue;
                String k = e.getKey();
                String v = e.getValue().getAsString();
                String kLower = k.toLowerCase(Locale.ROOT);
                if (!includeAll && !isUsefulGuideLangEntry(kLower, v)) {
                    continue;
                }
                list.add(new LangEntry(
                    k,
                    kLower,
                    v,
                    v.toLowerCase(Locale.ROOT)
                ));
            }
            return List.copyOf(list);
        } catch (Exception e) {
            McInsightConstants.LOG.warn("Failed to parse lang JSON ({} chars): {}", content.length(), e.getMessage());
            return List.of();
        }
    }

    private static String extractSnippet(String content, int index, int radius) {
        int start = Math.max(0, index - radius / 2);
        int end = Math.min(content.length(), index + radius);
        return content.substring(start, end).replaceAll("\\s+", " ").trim();
    }
}
