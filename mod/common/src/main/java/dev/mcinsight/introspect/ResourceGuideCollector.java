package dev.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcinsight.platform.Services;
import dev.mcinsight.server.JsonUtil;
import dev.mcinsight.server.MainThreadExecutor;
import dev.mcinsight.server.PerfMetrics;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class ResourceGuideCollector {

    private static final int MAX_PAGE_BYTES = 131072;
    private static final int MAX_CACHED_PAGES = 256;

    private static final AtomicReference<GuideIndex> INDEX_REF = new AtomicReference<>();
    private static final Object INDEX_BUILD_LOCK = new Object();

    private ResourceGuideCollector() {}

    public static void invalidateCache() {
        INDEX_REF.set(null);
    }

    private record GuideEntry(
        String id,
        String idLower,
        String source,
        String modId,
        String modIdLower,
        String displayPath,
        ResourceLocation rl,
        Path fsPath,
        String langCodeLower
    ) {}

    private record LangEntry(String key, String keyLower, String value, String valueLower) {}

    private static final class GuideIndex {
        final long generation;
        final ResourceManager resourceManager;
        final List<GuideEntry> entries;
        final Map<String, GuideEntry> byId;
        final ConcurrentHashMap<String, String> contentCache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, String> contentLowerCache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, List<LangEntry>> langCache = new ConcurrentHashMap<>();

        GuideIndex(long generation, ResourceManager resourceManager, List<GuideEntry> entries) {
            this.generation = generation;
            this.resourceManager = resourceManager;
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
            String loaded = readEntryContent(resourceManager, entry);
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

        List<LangEntry> getLangEntries(GuideEntry ge) {
            List<LangEntry> cached = langCache.get(ge.id());
            if (cached != null) {
                return cached;
            }
            String content = getContent(ge);
            if (content == null) {
                return List.of();
            }
            List<LangEntry> parsed = parseLangEntries(content);
            langCache.putIfAbsent(ge.id(), parsed);
            return parsed;
        }
    }

    private static GuideIndex getOrBuildIndex(MainThreadExecutor executor) throws Exception {
        long gen = InsightCacheManager.getResourceGeneration();
        GuideIndex existing = INDEX_REF.get();
        if (existing != null && existing.generation == gen) {
            PerfMetrics.markCacheHit();
            return existing;
        }

        synchronized (INDEX_BUILD_LOCK) {
            gen = InsightCacheManager.getResourceGeneration();
            existing = INDEX_REF.get();
            if (existing != null && existing.generation == gen) {
                PerfMetrics.markCacheHit();
                return existing;
            }

            // Step 1: Tiny main-thread hop only to grab ResourceManager reference
            ResourceManager rm = executor.supplyOnGameThread(
                () -> GameStateSupport.getResourceManager(executor),
                10000
            );
            if (rm == null) {
                return null;
            }

            // Step 2: Off-thread resource & FTB Quests directory indexing
            List<GuideEntry> entries = collectGuideEntries(rm);
            GuideIndex built = new GuideIndex(gen, rm, entries);
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
        String lang,
        int limit
    ) throws Exception {
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normQ.isEmpty()) {
            return JsonUtil.errorObject("INVALID_QUERY", "Query parameter 'q' is required.");
        }
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
            if ("lang".equals(ge.source())) {
                if (!"all".equals(normLang)
                    && !normLang.equals(ge.langCodeLower())
                    && !"en_us".equals(ge.langCodeLower())) {
                    continue;
                }
                List<LangEntry> langEntries = index.getLangEntries(ge);
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

    private static List<GuideEntry> collectGuideEntries(ResourceManager rm) {
        Map<String, GuideEntry> map = new LinkedHashMap<>();

        scanPrefix(rm, "patchouli_books", "patchouli", map);
        scanPrefix(rm, "ae2guide", "guideme", map);
        scanPrefix(rm, "guides", "guideme", map);
        scanPrefix(rm, "books", "oracle", map);
        scanPrefix(rm, "ponder", "ponder", map);

        rm.listResources("lang", path -> path.getPath().endsWith(".json")).forEach((rl, res) -> {
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
                rl,
                null,
                langCode
            ));
        });

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
        String prefix,
        String sourceTag,
        Map<String, GuideEntry> out
    ) {
        rm.listResources(prefix, path -> {
            String p = path.getPath().toLowerCase(Locale.ROOT);
            return p.endsWith(".json") || p.endsWith(".md") || p.endsWith(".txt");
        }).forEach((rl, res) -> {
            String id = sourceTag + ":" + rl;
            out.putIfAbsent(id, new GuideEntry(
                id,
                id.toLowerCase(Locale.ROOT),
                sourceTag,
                rl.getNamespace(),
                rl.getNamespace().toLowerCase(Locale.ROOT),
                rl.toString(),
                rl,
                null,
                null
            ));
        });
    }

    private static String readEntryContent(ResourceManager rm, GuideEntry entry) {
        try {
            if (entry.fsPath() != null) {
                byte[] bytes = Files.readAllBytes(entry.fsPath());
                int len = Math.min(bytes.length, MAX_PAGE_BYTES);
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            if (entry.rl() != null) {
                var opt = rm.getResource(entry.rl());
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

    private static List<LangEntry> parseLangEntries(String content) {
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) return List.of();
            JsonObject obj = parsed.getAsJsonObject();
            List<LangEntry> list = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String k = e.getKey();
                if (!(k.contains(".ponder.") || k.contains("tooltip") || k.contains("guide")
                    || k.contains("jei.") || k.contains("book") || k.contains("description"))) {
                    continue;
                }
                if (!e.getValue().isJsonPrimitive()) continue;
                String v = e.getValue().getAsString();
                list.add(new LangEntry(
                    k,
                    k.toLowerCase(Locale.ROOT),
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
