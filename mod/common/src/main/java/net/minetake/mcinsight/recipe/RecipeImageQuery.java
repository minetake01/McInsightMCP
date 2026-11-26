package net.minetake.mcinsight.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.ItemFilter;
import net.minetake.mcinsight.api.ItemQuery;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.PartialData;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.compat.IRecipePainter;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.ViewerModel;
import net.minetake.mcinsight.compat.ViewerRegistry;
import net.minetake.mcinsight.render.CapturedImage;
import net.minetake.mcinsight.render.PngEncoder;
import net.minetake.mcinsight.render.RecipeCanvas;

/**
 * Answers "show me the diagram" for one item.
 *
 * <p>Only a viewer can draw the GUI its recipes belong to, so this query selects recipes through the
 * same {@link ViewerQuery} semantics {@code recipes_query} uses and then lets the viewer that owns
 * each recipe paint it. A recipe no active viewer can paint is reported per recipe, not by failing
 * the whole request: one undrawable category must not hide the others.
 *
 * <p>Drawings are started one at a time, one per client frame. The request thread waits for the
 * bytes; the client render thread only prepares and records a drawing, and on {@code 26.x} the
 * engine's read-back completes on a later frame without this code blocking it.
 */
public final class RecipeImageQuery {

    /** A larger PNG than this is not sent; a caller that asked for a diagram gets an explanation. */
    private static final int MAX_IMAGE_BYTES = 512 * 1024;
    /** The whole request waits at most this long for the client to draw and read back. */
    private static final long RENDER_TIMEOUT_MS = 15_000L;
    private static final int RENDER_SCALE = 2;

    private RecipeImageQuery() {}

    private record Candidate(IRecipeViewerBridge bridge, ViewerModel.Category category, ViewerModel.Recipe recipe) {}

    private record Image(String viewer, String categoryUid, String categoryTitle, String recipeId, int width, int height, byte[] png) {}

    /** One drawing that has been started; its pixels arrive when the engine's read-back completes. */
    private record Pending(Candidate candidate, int width, int height, CompletableFuture<CapturedImage> pixels) {}

    /** What one request produced, failures included, so the caller can report both. */
    private static final class Rendered {
        final List<Image> images = new ArrayList<>();
        final PartialData missing = new PartialData();
        InsightException firstFailure;
    }

    public static JsonObject render(RequestContext ctx, Params params) throws Exception {
        ItemFilter item = ItemFilter.of(ItemQuery.parse(params.required("item", InsightError.INVALID_ID)));
        ViewerQuery.RoleScope role = roleOf(params.string("role"));
        String type = params.string("type");
        String source = params.stringOr("source", "viewer").toLowerCase(Locale.ROOT);
        int limit = InsightEndpoint.RECIPES_RENDER.limit(params);
        int offset = params.offset();

        if ("vanilla".equals(source)) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "The vanilla recipe manager has no GUI layout to draw; use source=\"viewer\" for images or mc_query_recipes for text."
            );
        }
        List<IRecipeViewerBridge> bridges = paintCapable(source);
        if (bridges.isEmpty()) {
            throw new InsightException(
                InsightError.VIEWER_UNAVAILABLE,
                "No active recipe viewer can draw recipe images (JEI, EMI, and REI are supported). Use mc_query_recipes to fetch recipe data as text instead."
            );
        }

        List<Candidate> candidates = candidates(bridges, item, role, type, offset, limit);
        boolean hasMore = candidates.size() == limit;
        Rendered rendered = new Rendered();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RENDER_TIMEOUT_MS);
        for (Candidate candidate : candidates) {
            Pending pending;
            try {
                pending = ctx.executor().supplyOnClientThread(() -> {
                    try {
                        return start(candidate);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, remainingMs(deadline));
            } catch (Exception e) {
                record(rendered, candidate, causeOf(e));
                continue;
            }
            if (pending == null) {
                rendered.missing.add(
                    part(candidate),
                    InsightError.DATA_UNAVAILABLE,
                    "The " + candidate.bridge().viewerName() + " category does not expose a drawable layout."
                );
                continue;
            }
            await(pending, rendered, deadline);
        }
        // Nothing drawable at all is an error, not an empty page: "no recipe matched" is a page with
        // no rows, which is a different answer from "the picture could not be produced".
        if (rendered.images.isEmpty() && rendered.firstFailure != null) {
            throw rendered.firstFailure;
        }

        JsonArray images = new JsonArray();
        for (Image image : rendered.images) {
            JsonObject entry = new JsonObject();
            entry.addProperty("viewer", image.viewer());
            entry.addProperty("categoryUid", image.categoryUid());
            if (image.categoryTitle() != null) {
                entry.addProperty("categoryTitle", image.categoryTitle());
            }
            if (image.recipeId() != null) {
                entry.addProperty("recipeId", image.recipeId());
            }
            entry.addProperty("width", image.width());
            entry.addProperty("height", image.height());
            entry.addProperty("png", Base64.getEncoder().encodeToString(image.png()));
            images.add(entry);
        }
        // A page of pictures: the scan stops when the page is full, so the total is not counted.
        JsonObject out = Page.uncounted("images", images, offset, hasMore);
        out.addProperty("format", "png");
        out.addProperty("scale", RENDER_SCALE);
        rendered.missing.applyTo(out);
        return out;
    }

    private static ViewerQuery.RoleScope roleOf(String raw) {
        return raw == null ? ViewerQuery.RoleScope.OUTPUT : ViewerQuery.RoleScope.parse(raw);
    }

    /** The adapters the source selects, narrowed to the ones that can actually paint. */
    private static List<IRecipeViewerBridge> paintCapable(String source) {
        List<IRecipeViewerBridge> resolved = "all".equals(source) || "viewer".equals(source)
            ? ViewerRegistry.active()
            : ViewerQuery.resolve(source);
        return resolved.stream().filter(bridge -> bridge.painter() != null).toList();
    }

    private static List<Candidate> candidates(
        List<IRecipeViewerBridge> bridges,
        ItemFilter item,
        ViewerQuery.RoleScope role,
        String type,
        int offset,
        int limit
    ) {
        String normType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        List<Candidate> out = new ArrayList<>();
        int matched = 0;
        outer:
        for (IRecipeViewerBridge bridge : bridges) {
            for (ViewerModel.Category category : bridge.listCategories()) {
                if (!normType.isEmpty() && !category.uid().toLowerCase(Locale.ROOT).equals(normType)) {
                    continue;
                }
                for (ViewerModel.Recipe recipe : bridge.recipesOf(category)) {
                    if (!ViewerQuery.matches(recipe, item, role)) {
                        continue;
                    }
                    if (matched < offset) {
                        matched++;
                        continue;
                    }
                    out.add(new Candidate(bridge, category, recipe));
                    if (out.size() >= limit) {
                        break outer;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Prepares and starts one drawing on the client render thread.
     *
     * <p>Returns {@code null} when the viewer has no drawable layout for the recipe; the caller turns
     * that into a {@code missing} entry.
     */
    private static Pending start(Candidate candidate) throws Exception {
        IRecipePainter.Painting painting = candidate.bridge().painter().prepare(candidate.recipe());
        if (painting == null) {
            return null;
        }
        try {
            int width = painting.width();
            int height = painting.height();
            return new Pending(candidate, width, height, RecipeCanvas.capture(width, height, RENDER_SCALE, painting::paint));
        } finally {
            painting.close();
        }
    }

    /** Waits for one drawing's pixels, encodes them off the render thread, and records the result. */
    private static void await(Pending pending, Rendered rendered, long deadline) {
        try {
            CapturedImage captured = pending.pixels().get(remainingMs(deadline), TimeUnit.MILLISECONDS);
            byte[] png = PngEncoder.encode(captured, pending.width() * RENDER_SCALE, pending.height() * RENDER_SCALE);
            if (png.length > MAX_IMAGE_BYTES) {
                rendered.missing.add(
                    part(pending.candidate()),
                    InsightError.DATA_UNAVAILABLE,
                    "The rendered image is " + png.length + " bytes, above the " + MAX_IMAGE_BYTES + " byte limit."
                );
                return;
            }
            rendered.images.add(new Image(
                pending.candidate().bridge().viewerName(),
                pending.candidate().recipe().categoryUid(),
                pending.candidate().recipe().categoryTitle(),
                pending.candidate().recipe().recipeId(),
                pending.width(),
                pending.height(),
                png
            ));
        } catch (TimeoutException e) {
            record(
                rendered,
                pending.candidate(),
                new InsightException(
                    InsightError.DATA_UNAVAILABLE,
                    "The client did not read the rendered image back in time."
                )
            );
        } catch (ExecutionException e) {
            record(rendered, pending.candidate(), causeOf(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record(
                rendered,
                pending.candidate(),
                new InsightException(InsightError.DATA_UNAVAILABLE, "Interrupted while waiting for the rendered image.")
            );
        } catch (Exception e) {
            record(rendered, pending.candidate(), e);
        }
    }

    private static void record(Rendered rendered, Candidate candidate, Throwable cause) {
        InsightException error = cause instanceof InsightException insight
            ? insight
            : new InsightException(InsightError.INTERNAL_ERROR, "Recipe image rendering failed: " + cause);
        if (rendered.firstFailure == null) {
            rendered.firstFailure = error;
        }
        rendered.missing.add(part(candidate), InsightError.DATA_UNAVAILABLE, String.valueOf(cause.getMessage()));
    }

    /** The failure behind a wrapped client-thread call, or the call's own failure. */
    private static Throwable causeOf(Throwable error) {
        return error.getCause() != null ? error.getCause() : error;
    }

    private static long remainingMs(long deadline) {
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static String part(Candidate candidate) {
        String recipeId = candidate.recipe().recipeId();
        return recipeId != null ? recipeId : candidate.recipe().categoryUid();
    }
}
