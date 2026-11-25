package net.minetake.mcinsight.guides;

import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The cached index of every guide-like document a pack ships.
 *
 * <p>Patchouli books, GuideME pages, FTB Quests chapters, Create Ponder entries, Oracle Index books,
 * and language files are indexed once here and keyed by a lowercase id, so listing, searching, and
 * reading agree about what an id is. Text is read lazily and cached per entry; the whole index is
 * rebuilt when the resource generation changes.
 */
public final class GuideIndex {

    private static final int MAX_PAGE_CHARS = 131_072;
    private static final int MAX_LANG_CHARS = 8 * 1024 * 1024;
    private static final int MAX_CACHED_PAGES = 256;
    private static final int MAX_CACHED_LANG_FILES = 256;
    private static final long MAIN_THREAD_TIMEOUT_MS = 10_000L;

    private static final GenerationalCache<GuideIndex> CACHE = new GenerationalCache<>(CacheGenerations::resource);

    /** Where a document's bytes live. */
    public enum Layout {
        ASSET,
        DATA,
        FILE
    }

    /** One indexed document; {@code source} names the guide system, not the file format. */
    public record Entry(
        String id,
        String idLower,
        String source,
        String modId,
        String modIdLower,
        String displayPath,
        String langCodeLower,
        Layout layout,
        ResourceLocation location,
        Path file
    ) {

        public boolean isLang() {
            return "lang".equals(source);
        }
    }

    /** The resource managers and language the index was built against. */
    private record Session(ResourceManager assets, ResourceManager data, String language) {}

    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final String language;
    private final ResourceManager assetResources;
    private final ResourceManager dataResources;
    private final Map<String, String> contentCache = newLru(MAX_CACHED_PAGES);
    private final Map<String, String> lowerContentCache = newLru(MAX_CACHED_PAGES);
    private final Map<String, List<LangEntries.Entry>> langCache = newLru(MAX_CACHED_LANG_FILES);
    private final Map<String, List<LangEntries.Entry>> allLangCache = newLru(MAX_CACHED_LANG_FILES);

    private GuideIndex(
        List<Entry> entries,
        String language,
        ResourceManager assetResources,
        ResourceManager dataResources
    ) {
        this.entries = entries;
        this.language = language;
        this.assetResources = assetResources;
        this.dataResources = dataResources;
        Map<String, Entry> lookup = new LinkedHashMap<>(entries.size() * 2);
        for (Entry entry : entries) {
            lookup.put(entry.idLower(), entry);
        }
        this.byId = Map.copyOf(lookup);
    }

    public static GuideIndex get(RequestContext ctx) {
        return CACHE.get(() -> build(ctx));
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Looks an id up case-insensitively, matching how list and search report ids. */
    public Entry find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return byId.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** The language search defaults to when the caller does not name one. */
    public String language() {
        return language;
    }

    /** The raw text of one document, or {@code null} when it cannot be read. */
    public String content(Entry entry) {
        if (!entry.isLang()) {
            String cached = contentCache.get(entry.id());
            if (cached != null) {
                return cached;
            }
        }
        String loaded = read(entry);
        if (!entry.isLang() && loaded != null) {
            contentCache.put(entry.id(), loaded);
        }
        return loaded;
    }

    /** The lowercase form of a document, cached so a search does not fold the same text twice. */
    public String lowerContent(Entry entry, String content) {
        String cached = lowerContentCache.get(entry.id());
        if (cached != null) {
            return cached;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        lowerContentCache.put(entry.id(), lower);
        return lower;
    }

    /** Entries of a language file, narrowed to documentation-like keys unless {@code includeAll}. */
    public List<LangEntries.Entry> langEntries(Entry entry, boolean includeAll) {
        Map<String, List<LangEntries.Entry>> target = includeAll ? allLangCache : langCache;
        List<LangEntries.Entry> cached = target.get(entry.id());
        if (cached != null) {
            return cached;
        }
        String text = content(entry);
        if (text == null) {
            return List.of();
        }
        List<LangEntries.Entry> parsed = LangEntries.parse(text, includeAll);
        target.put(entry.id(), parsed);
        return parsed;
    }

    private static GuideIndex build(RequestContext ctx) {
        Session session = openSession(ctx);
        Path questsDirectory = Services.PLATFORM.getConfigDir().resolve("ftbquests").resolve("quests");
        boolean hasQuests = Files.isDirectory(questsDirectory);
        if (session.assets() == null && session.data() == null && !hasQuests) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "No resource manager is available, so guides cannot be listed."
            );
        }

        Map<String, Entry> guides = new LinkedHashMap<>();
        if (session.assets() != null) {
            scanPrefix(session.assets(), Layout.ASSET, "patchouli_books", "patchouli", guides);
            scanPrefix(session.assets(), Layout.ASSET, "ae2guide", "guideme", guides);
            scanPrefix(session.assets(), Layout.ASSET, "guides", "guideme", guides);
            scanPrefix(session.assets(), Layout.ASSET, "books", "oracle", guides);
            scanPrefix(session.assets(), Layout.ASSET, "ponder", "ponder", guides);
        }
        if (session.data() != null) {
            scanPrefix(session.data(), Layout.DATA, "patchouli_books", "patchouli", guides);
            scanPrefix(session.data(), Layout.DATA, "ae2guide", "guideme", guides);
            scanPrefix(session.data(), Layout.DATA, "guides", "guideme", guides);
            scanPrefix(session.data(), Layout.DATA, "books", "oracle", guides);
            scanPrefix(session.data(), Layout.DATA, "ponder", "ponder", guides);
        }
        scanQuests(questsDirectory, guides);

        List<Entry> ordered = new ArrayList<>(guides.size() + 64);
        ordered.addAll(guides.values());
        ordered.addAll(scanLanguageFiles(session.assets()));

        return new GuideIndex(List.copyOf(ordered), session.language(), session.assets(), session.data());
    }

    /**
     * Takes the resource manager references and the active language in one short game-thread hop.
     *
     * <p>Both are game state; reading them while the index is built off-thread would race with a
     * reload that is swapping the very managers being read.
     */
    private static Session openSession(RequestContext ctx) {
        try {
            return ctx.executor().supplyOnGameThread(() -> new Session(
                GameAccess.getAssetResourceManager(ctx.executor()),
                GameAccess.getDataResourceManager(ctx.executor()),
                currentLanguage()
            ), MAIN_THREAD_TIMEOUT_MS);
        } catch (Exception e) {
            throw new InsightException(InsightError.DATA_UNAVAILABLE, "The game thread did not answer: " + e.getMessage());
        }
    }

    private static String currentLanguage() {
        if (!Services.PLATFORM.isClientSide()) {
            return "en_us";
        }
        String code = ClientStateAccessor.getLanguageCode();
        return code == null || code.isBlank() ? "en_us" : code.trim().toLowerCase(Locale.ROOT);
    }

    private static void scanPrefix(
        ResourceManager resources,
        Layout layout,
        String prefix,
        String source,
        Map<String, Entry> out
    ) {
        resources.listResources(prefix, path -> {
            String lower = path.getPath().toLowerCase(Locale.ROOT);
            return lower.endsWith(".json") || lower.endsWith(".md") || lower.endsWith(".txt");
        }).forEach((location, resource) -> {
            String id = layout == Layout.DATA ? source + ":data/" + location : source + ":" + location;
            String displayPath = layout == Layout.DATA ? "data/" + location : location.toString();
            out.putIfAbsent(id, new Entry(
                id,
                id.toLowerCase(Locale.ROOT),
                source,
                location.getNamespace(),
                location.getNamespace().toLowerCase(Locale.ROOT),
                displayPath,
                "",
                layout,
                location,
                null
            ));
        });
    }

    private static void scanQuests(Path questsDirectory, Map<String, Entry> out) {
        if (!Files.isDirectory(questsDirectory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(questsDirectory, 10)) {
            walk.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".snbt"))
                .forEach(path -> {
                    String relative = questsDirectory.relativize(path).toString().replace('\\', '/');
                    String id = "ftbquests:" + relative;
                    out.putIfAbsent(id, new Entry(
                        id,
                        id.toLowerCase(Locale.ROOT),
                        "ftbquests",
                        "ftbquests",
                        "ftbquests",
                        relative,
                        "",
                        Layout.FILE,
                        null,
                        path
                    ));
                });
        } catch (Exception e) {
            McInsightConstants.LOG.warn("Failed scanning FTB Quests directory {}: {}", questsDirectory, e.getMessage());
        }
    }

    private static List<Entry> scanLanguageFiles(ResourceManager resources) {
        if (resources == null) {
            return List.of();
        }
        Map<String, Entry> found = new LinkedHashMap<>();
        resources.listResources("lang", path -> path.getPath().endsWith(".json")).forEach((location, resource) -> {
            String path = location.getPath();
            int prefixLength = "lang/".length();
            if (!path.startsWith("lang/") || path.length() <= prefixLength + ".json".length()) {
                return;
            }
            String langCode = path.substring(prefixLength, path.length() - ".json".length());
            String id = "lang:" + location;
            found.putIfAbsent(id, new Entry(
                id,
                id.toLowerCase(Locale.ROOT),
                "lang",
                location.getNamespace(),
                location.getNamespace().toLowerCase(Locale.ROOT),
                location.toString(),
                langCode.toLowerCase(Locale.ROOT),
                Layout.ASSET,
                location,
                null
            ));
        });
        return List.copyOf(found.values());
    }

    private String read(Entry entry) {
        try {
            if (entry.layout() == Layout.FILE && entry.file() != null) {
                try (BufferedReader reader = Files.newBufferedReader(entry.file(), StandardCharsets.UTF_8)) {
                    return readBounded(reader, MAX_LANG_CHARS, entry.id());
                }
            }
            ResourceManager resources = entry.layout() == Layout.DATA ? dataResources : assetResources;
            if (resources == null || entry.location() == null) {
                return null;
            }
            Optional<Resource> resource = resources.getResource(entry.location());
            if (resource.isEmpty()) {
                return null;
            }
            int maxChars = entry.isLang() ? MAX_LANG_CHARS : MAX_PAGE_CHARS;
            try (InputStream stream = resource.get().open();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return readBounded(reader, maxChars, entry.id());
            }
        } catch (Exception e) {
            McInsightConstants.LOG.warn("Failed reading guide entry '{}': {}", entry.id(), e.getMessage());
            return null;
        }
    }

    private static String readBounded(BufferedReader reader, int maxChars, String label) throws Exception {
        StringBuilder builder = new StringBuilder(4096);
        char[] buffer = new char[8192];
        int remaining = maxChars;
        while (remaining > 0) {
            int read = reader.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read <= 0) {
                break;
            }
            builder.append(buffer, 0, read);
            remaining -= read;
        }
        if (remaining == 0 && reader.read() != -1) {
            McInsightConstants.LOG.warn("Guide resource '{}' exceeded {} chars and was truncated.", label, maxChars);
        }
        return builder.toString();
    }

    private static <V> Map<String, V> newLru(int maxSize) {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxSize;
            }
        });
    }

    /** The JSON form of an entry, shared by list and page so both describe a guide the same way. */
    static JsonObject describe(Entry entry) {
        JsonObject out = new JsonObject();
        out.addProperty("id", entry.id());
        out.addProperty("source", entry.source());
        out.addProperty("modId", entry.modId());
        out.addProperty("path", entry.displayPath());
        return out;
    }
}
