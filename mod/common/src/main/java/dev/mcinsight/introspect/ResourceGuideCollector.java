package dev.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcinsight.platform.Services;
import dev.mcinsight.server.JsonUtil;
import dev.mcinsight.server.MainThreadExecutor;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class ResourceGuideCollector {

    private static final int MAX_PAGE_BYTES = 131072;

    private ResourceGuideCollector() {}

    public static JsonObject listGuides(
        MainThreadExecutor executor,
        String sourceFilter,
        String modFilter
    ) throws Exception {
        String normSource = sourceFilter == null ? "all" : sourceFilter.trim().toLowerCase(Locale.ROOT);
        String normMod = modFilter == null ? "" : modFilter.trim().toLowerCase(Locale.ROOT);

        return executor.supplyOnGameThread(() -> {
            ResourceManager rm = GameStateSupport.getResourceManager(executor);
            if (rm == null) {
                return JsonUtil.errorObject("NO_RESOURCES", "ResourceManager is not available.");
            }
            List<GuideEntry> entries = collectGuideEntries(rm, normSource, normMod, "all");
            JsonArray arr = new JsonArray();
            for (GuideEntry ge : entries) {
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
        }, 10000);
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

        return executor.supplyOnGameThread(() -> {
            ResourceManager rm = GameStateSupport.getResourceManager(executor);
            if (rm == null) {
                return JsonUtil.errorObject("NO_RESOURCES", "ResourceManager is not available.");
            }
            List<GuideEntry> entries = collectGuideEntries(rm, "all", "", normLang);
            JsonArray hits = new JsonArray();

            for (GuideEntry ge : entries) {
                if (hits.size() >= clampedLimit) {
                    break;
                }
                String content = readEntryContent(rm, ge);
                if (content == null) {
                    continue;
                }

                if ("lang".equals(ge.source())) {
                    searchLangJson(ge, content, normQ, hits, clampedLimit);
                } else {
                    String lower = content.toLowerCase(Locale.ROOT);
                    int idx = lower.indexOf(normQ);
                    if (idx >= 0 || ge.id().toLowerCase(Locale.ROOT).contains(normQ)) {
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
        }, 15000);
    }

    public static JsonObject getGuidePage(MainThreadExecutor executor, String guideId) throws Exception {
        if (guideId == null || guideId.isBlank()) {
            return JsonUtil.errorObject("INVALID_ID", "Guide 'id' is required.");
        }
        return executor.supplyOnGameThread(() -> {
            ResourceManager rm = GameStateSupport.getResourceManager(executor);
            if (rm == null) {
                return JsonUtil.errorObject("NO_RESOURCES", "ResourceManager is not available.");
            }
            List<GuideEntry> entries = collectGuideEntries(rm, "all", "", "all");
            for (GuideEntry ge : entries) {
                if (ge.id().equals(guideId)) {
                    String content = readEntryContent(rm, ge);
                    JsonObject out = new JsonObject();
                    out.addProperty("ok", true);
                    out.addProperty("id", ge.id());
                    out.addProperty("source", ge.source());
                    out.addProperty("modId", ge.modId());
                    out.addProperty("path", ge.displayPath());
                    out.addProperty("content", content != null ? content : "");
                    return out;
                }
            }
            return JsonUtil.errorObject("NOT_FOUND", "Guide page not found: " + guideId);
        }, 10000);
    }

    private record GuideEntry(String id, String source, String modId, String displayPath, ResourceLocation rl, Path fsPath) {}

    private static List<GuideEntry> collectGuideEntries(
        ResourceManager rm,
        String normSource,
        String normMod,
        String normLang
    ) {
        Map<String, GuideEntry> map = new LinkedHashMap<>();

        if ("all".equals(normSource) || "patchouli".equals(normSource) || "guideme".equals(normSource)
            || "oracle".equals(normSource) || "ponder".equals(normSource)) {
            scanPrefix(rm, "patchouli_books", "patchouli", normSource, normMod, map);
            scanPrefix(rm, "ae2guide", "guideme", normSource, normMod, map);
            scanPrefix(rm, "guides", "guideme", normSource, normMod, map);
            scanPrefix(rm, "books", "oracle", normSource, normMod, map);
            scanPrefix(rm, "ponder", "ponder", normSource, normMod, map);
        }

        if ("all".equals(normSource) || "lang".equals(normSource)) {
            rm.listResources("lang", path -> path.getPath().endsWith(".json")).forEach((rl, res) -> {
                String ns = rl.getNamespace().toLowerCase(Locale.ROOT);
                if (!normMod.isEmpty() && !ns.equals(normMod)) return;
                String p = rl.getPath().toLowerCase(Locale.ROOT);
                if (!"all".equals(normLang) && !p.equals("lang/" + normLang + ".json") && !p.equals("lang/en_us.json")) {
                    return;
                }
                String id = "lang:" + rl;
                map.putIfAbsent(id, new GuideEntry(id, "lang", rl.getNamespace(), rl.toString(), rl, null));
            });
        }

        if ("all".equals(normSource) || "ftbquests".equals(normSource)) {
            Path questsDir = Services.PLATFORM.getGameDir().resolve("config").resolve("ftbquests").resolve("quests");
            if (Files.isDirectory(questsDir)) {
                try (Stream<Path> walk = Files.walk(questsDir, 4)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".snbt"))
                        .forEach(p -> {
                            String rel = questsDir.relativize(p).toString().replace('\\', '/');
                            if (!normMod.isEmpty() && !"ftbquests".equals(normMod)) return;
                            String id = "ftbquests:" + rel;
                            map.putIfAbsent(id, new GuideEntry(id, "ftbquests", "ftbquests", rel, null, p));
                        });
                } catch (Exception ignored) {}
            }
        }

        return new ArrayList<>(map.values());
    }

    private static void scanPrefix(
        ResourceManager rm,
        String prefix,
        String sourceTag,
        String normSource,
        String normMod,
        Map<String, GuideEntry> out
    ) {
        if (!"all".equals(normSource) && !sourceTag.equals(normSource)) {
            return;
        }
        rm.listResources(prefix, path -> {
            String p = path.getPath().toLowerCase(Locale.ROOT);
            return p.endsWith(".json") || p.endsWith(".md") || p.endsWith(".txt");
        }).forEach((rl, res) -> {
            String ns = rl.getNamespace().toLowerCase(Locale.ROOT);
            if (!normMod.isEmpty() && !ns.equals(normMod)) return;
            String id = sourceTag + ":" + rl;
            out.putIfAbsent(id, new GuideEntry(id, sourceTag, rl.getNamespace(), rl.toString(), rl, null));
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
                    int read = reader.read(buf);
                    return read > 0 ? new String(buf, 0, read) : "";
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void searchLangJson(
        GuideEntry ge,
        String content,
        String normQ,
        JsonArray hits,
        int limit
    ) {
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) return;
            JsonObject obj = parsed.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (hits.size() >= limit) break;
                String k = e.getKey();
                if (!(k.contains(".ponder.") || k.contains("tooltip") || k.contains("guide")
                    || k.contains("jei.") || k.contains("book") || k.contains("description"))) {
                    continue;
                }
                if (!e.getValue().isJsonPrimitive()) continue;
                String v = e.getValue().getAsString();
                if (v.toLowerCase(Locale.ROOT).contains(normQ) || k.toLowerCase(Locale.ROOT).contains(normQ)) {
                    JsonObject hit = new JsonObject();
                    hit.addProperty("id", ge.id());
                    hit.addProperty("source", "lang");
                    hit.addProperty("modId", ge.modId());
                    hit.addProperty("key", k);
                    hit.addProperty("snippet", v.length() > 300 ? v.substring(0, 300) + "..." : v);
                    hits.add(hit);
                }
            }
        } catch (Exception ignored) {}
    }

    private static String extractSnippet(String content, int index, int radius) {
        int start = Math.max(0, index - radius / 2);
        int end = Math.min(content.length(), index + radius);
        return content.substring(start, end).replaceAll("\\s+", " ").trim();
    }
}
