package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import net.minetake.mcinsight.server.PerfMetrics;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class ResourceGuideCollector {

    private static final int MAX_PAGE_BYTES = 131072;
    private static final int MAX_CACHED_PAGES = 256;
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

    private record LangEntry(String key, String keyLower, String value, String valueLower) {}

    private static final class GuideIndex {
        final long resourceGeneration;
        final long dataGeneration;
        final ResourceManager assetRm;
        final ResourceManager dataRm;
        final List<GuideEntry> entries;
        final Map<String, GuideEntry> byId;
        final ConcurrentHashMap<String, String> contentCache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, String> contentLowerCache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, List<LangEntry>> langCache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, List<LangEntry>> allLangCache = new ConcurrentHashMap<>();

        GuideIndex(
            long resourceGeneration,
            long dataGeneration,
            ResourceManager assetRm,
            ResourceManager dataRm,
            List<GuideEntry> entries
        ) {
            this.resourceGeneration = resourceGeneration;
            this.dataGeneration = dataGeneration;
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
            String cached = contentCache.get(entry.id());
            if (cached != null) {
                return cached;
            }
            String loaded = readEntryContent(assetRm, dataRm, entry);
            if (loaded != null && contentCache.size() < MAX_CACHED_PAGES) {
                contentCache.putIfAbsent(entry.id(), loaded);
            }
            return loaded;
        }

        String getContentLower(GuideEntry entry, String content) {
            String cached = contentLowerCache.get(entry.id());
            if (cached != null) {
                return cached;
            }
            String lower = content.toLowerCase(Locale.ROOT);
            if (contentLowerCache.size() < MAX_CACHED_PAGES) {
                contentLowerCache.putIfAbsent(entry.id(), lower);
            }
            return lower;
        }

        List<LangEntry> getLangEntries(GuideEntry ge, boolean includeAll) {
            ConcurrentHashMap<String, List<LangEntry>> targetCache = includeAll ? allLangCache : langCache;
            List<LangEntry> cached = targetCache.get(ge.id());
            if (cached != null) {
                return cached;
            }
            String content = getContent(ge);
            if (content == null) {
                return List.of();
            }
            List<LangEntry> parsed = parseLangEntries(content, includeAll);
            targetCache.putIfAbsent(ge.id(), parsed);
            return parsed;
        }
    }

    private record ManagerPair(ResourceManager assetRm, ResourceManager dataRm) {}

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

            // Step 1: Tiny main-thread hop only to grab ResourceManager references
            ManagerPair rms = executor.supplyOnGameThread(
                () -> new ManagerPair(
                    GameStateSupport.getAssetResourceManager(executor),
                    GameStateSupport.getDataResourceManager(executor)
                ),
                10000
            );
            if (rms == null || (rms.assetRm() == null && rms.dataRm() == null)) {
                return null;
            }

            // Step 2: Off-thread resource & FTB Quests directory indexing
            List<GuideEntry> entries = collectGuideEntries(rms.assetRm(), rms.dataRm());
            GuideIndex built = new GuideIndex(resGen, dataGen, rms.assetRm(), rms.dataRm(), entries);
            INDEX_REF.set(built);
            return built;
        }
    }

    public static JsonObject listGuides(
        MainThreadExecutor executor,
        String sourceFilter,
        String modFilter
    ) throws Exception {
        String normSource = sourceFilter == null ? "all" : sourceFilter.trim().toLowerCase(Locale.ROOT);
        String normMod = modFilter == null ? "" : modFilter.trim().toLowerCase(Locale.ROOT);

        GuideIndex index = getOrBuildIndex(executor);
        if (index == null) {
            return JsonUtil.errorObject("NO_RESOURCES", "ResourceManager is not available.");
        }

        JsonArray arr = new JsonArray();
        for (GuideEntry ge : index.entries) {
            if (!"all".equals(normSource) && !ge.source().equals(normSource)) continue;
            if (!normMod.isEmpty() && !ge.modIdLower().equals(normMod)) continue;
            JsonObject obj = new JsonObject();
            obj.addProperty("id", ge.id());
            obj.addProperty("source", ge.source());
            obj.addProperty("modId", ge.modId());
            obj.addProperty("path", ge.displayPath());
            arr.add(obj);
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("count", arr.size());
        out.add("guides", arr);
        return out;
    }

    public static JsonObject searchGuides(
        MainThreadExecutor executor,
        String query,
        String sourceFilter,
        String lang,
        boolean includeAllLang,
        int limit
    ) throws Exception {
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normQ.isEmpty()) {
            return JsonUtil.errorObject("INVALID_QUERY", "Query parameter 'q' is required.");
        }
        String normSource = (sourceFilter == null || sourceFilter.isBlank())
            ? "all"
            : sourceFilter.trim().toLowerCase(Locale.ROOT);
        String normLang = (lang == null || lang.isBlank()) ? "ja_jp" : lang.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 50));

        GuideIndex index = getOrBuildIndex(executor);
        if (index == null) {
            return JsonUtil.errorObject("NO_RESOURCES", "ResourceManager is not available.");
        }

        JsonArray hits = new JsonArray();
        for (GuideEntry ge : index.entries) {
            if (hits.size() >= clampedLimit) {
                break;
            }
            if (!"all".equals(normSource) && !ge.source().equals(normSource)) {
                continue;
            }
            if ("lang".equals(ge.source())) {
                if (!"all".equals(normLang)
                    && !normLang.equals(ge.langCodeLower())
                    && !"en_us".equals(ge.langCodeLower())) {
                    continue;
                }
                List<LangEntry> langEntries = index.getLangEntries(ge, includeAllLang);
                for (LangEntry le : langEntries) {
                    if (hits.size() >= clampedLimit) break;
                    if (le.valueLower().contains(normQ) || le.keyLower().contains(normQ)) {
                        JsonObject hit = new JsonObject();
                        hit.addProperty("id", ge.id());
                        hit.addProperty("source", "lang");
                        hit.addProperty("modId", ge.modId());
                        hit.addProperty("key", le.key());
                        String v = le.value();
                        hit.addProperty("snippet", v.length() > 300 ? v.substring(0, 300) + "..." : v);
                        hits.add(hit);
                    }
                }
            } else {
                String content = index.getContent(ge);
                if (content == null) continue;
                String lower = index.getContentLower(ge, content);
                int idx = lower.indexOf(normQ);
                if (idx >= 0 || ge.idLower().contains(normQ)) {
                    JsonObject hit = new JsonObject();
                    hit.addProperty("id", ge.id());
                    hit.addProperty("source", ge.source());
                    hit.addProperty("modId", ge.modId());
                    hit.addProperty("snippet", idx >= 0 ? extractSnippet(content, idx, 240) : ge.displayPath());
                    hits.add(hit);
                }
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("query", query);
        out.addProperty("count", hits.size());
        out.add("hits", hits);
        return out;
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

    private static List<GuideEntry> collectGuideEntries(ResourceManager assetRm, ResourceManager dataRm) {
        Map<String, GuideEntry> map = new LinkedHashMap<>();

        if (assetRm != null) {
            scanPrefix(assetRm, ManagerKind.ASSET, "patchouli_books", "patchouli", map);
            scanPrefix(assetRm, ManagerKind.ASSET, "ae2guide", "guideme", map);
            scanPrefix(assetRm, ManagerKind.ASSET, "guides", "guideme", map);
            scanPrefix(assetRm, ManagerKind.ASSET, "books", "oracle", map);
            scanPrefix(assetRm, ManagerKind.ASSET, "ponder", "ponder", map);

            assetRm.listResources("lang", path -> path.getPath().endsWith(".json")).forEach((rl, res) -> {
                String p = rl.getPath().toLowerCase(Locale.ROOT);
                String langCode = "";
                if (p.startsWith("lang/") && p.endsWith(".json") && p.length() > 10) {
                    langCode = p.substring(5, p.length() - 5);
                }
                String id = "lang:" + rl;
                map.putIfAbsent(id, new GuideEntry(
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
            scanPrefix(dataRm, ManagerKind.DATA, "patchouli_books", "patchouli", map);
            scanPrefix(dataRm, ManagerKind.DATA, "ae2guide", "guideme", map);
            scanPrefix(dataRm, ManagerKind.DATA, "guides", "guideme", map);
            scanPrefix(dataRm, ManagerKind.DATA, "books", "oracle", map);
            scanPrefix(dataRm, ManagerKind.DATA, "ponder", "ponder", map);
        }

        Path questsDir = Services.PLATFORM.getGameDir().resolve("config").resolve("ftbquests").resolve("quests");
        if (Files.isDirectory(questsDir)) {
            try (Stream<Path> walk = Files.walk(questsDir, 4)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".snbt"))
                    .forEach(p -> {
                        String rel = questsDir.relativize(p).toString().replace('\\', '/');
                        String id = "ftbquests:" + rel;
                        map.putIfAbsent(id, new GuideEntry(
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
            } catch (Exception ignored) {}
        }

        return List.copyOf(map.values());
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

    private static String readEntryContent(ResourceManager assetRm, ResourceManager dataRm, GuideEntry entry) {
        try {
            if (entry.managerKind() == ManagerKind.FILE && entry.fsPath() != null) {
                byte[] bytes = Files.readAllBytes(entry.fsPath());
                int len = Math.min(bytes.length, MAX_PAGE_BYTES);
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            ResourceManager targetRm = entry.managerKind() == ManagerKind.DATA ? dataRm : assetRm;
            if (targetRm != null && entry.rl() != null) {
                Optional<Resource> opt = targetRm.getResource(entry.rl());
                if (opt.isEmpty()) return null;
                Resource res = opt.get();
                try (InputStream is = res.open();
                     InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    char[] buf = new char[MAX_PAGE_BYTES];
                    int total = 0;
                    while (total < buf.length) {
                        int n = reader.read(buf, total, buf.length - total);
                        if (n <= 0) break;
                        total += n;
                    }
                    return total > 0 ? new String(buf, 0, total) : "";
                }
            }
        } catch (Exception ignored) {}
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

    private static List<LangEntry> parseLangEntries(String content, boolean includeAll) {
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
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String extractSnippet(String content, int index, int radius) {
        int start = Math.max(0, index - radius / 2);
        int end = Math.min(content.length(), index + radius);
        return content.substring(start, end).replaceAll("\\s+", " ").trim();
    }
}
