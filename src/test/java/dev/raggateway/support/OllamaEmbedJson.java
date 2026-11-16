package dev.raggateway.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Builds Ollama {@code POST /api/embed} bodies for WireMock (t09 round prep). Ollama is not reachable in the
 * build environment (D-21), so the shape is taken from the official API docs, not from memory:
 * {@code docs/api.md}, "Generate Embeddings", in github.com/ollama/ollama at commit db5266a7, read 2026-11-16.
 *
 * <ul>
 *   <li>Request: {@code {"model": "...", "input": "text" | ["text", ...]}}; optional {@code truncate}
 *       (default true: inputs longer than the context are cut silently), {@code options},
 *       {@code keep_alive}, {@code dimensions}.</li>
 *   <li>Response: {@code {"model": "...", "embeddings": [[...], ...]}} in input order, plus
 *       {@code total_duration}, {@code load_duration} (ns) and {@code prompt_eval_count}.</li>
 *   <li>The older {@code POST /api/embeddings} ({@code prompt}, one {@code embedding}) is documented as
 *       superseded; the adapter does not use it.</li>
 *   <li>Errors: an HTTP status with {@code {"error": "..."}}, as for {@code /api/chat}.</li>
 * </ul>
 *
 * <pre>{@code
 * ollama.stubFor(post("/api/embed").willReturn(okJson(OllamaEmbedJson.response("nomic-embed-text", 2, 768))));
 * }</pre>
 */
public final class OllamaEmbedJson {

    public static final String MODEL = "nomic-embed-text";
    private static final ObjectMapper JSON = new ObjectMapper();

    private OllamaEmbedJson() {
    }

    /** A response with {@code count} vectors of {@code dims} values (seeded, so bodies are reproducible). */
    public static String response(String model, int count, int dims) {
        float[][] vectors = new float[count][];
        for (int i = 0; i < count; i++) vectors[i] = vector(dims, i);
        return response(model, List.of(vectors));
    }

    public static String response(String model, List<float[]> vectors) {
        ObjectNode root = JSON.createObjectNode().put("model", model);
        ArrayNode embeddings = root.putArray("embeddings");
        for (float[] v : vectors) {
            ArrayNode row = embeddings.addArray();
            for (float x : v) row.add(x);
        }
        root.put("total_duration", 14_143_917L).put("load_duration", 1_019_500L).put("prompt_eval_count", 8);
        return root.toString();
    }

    /** An error body as Ollama sends it with a non-2xx status. */
    public static String error(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    /** A unit-length vector from a seed. */
    public static float[] vector(int dims, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        float[] v = new float[dims];
        double norm = 0;
        for (int i = 0; i < dims; i++) {
            v[i] = (float) (random.nextDouble() * 2 - 1);
            norm += v[i] * v[i];
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < dims; i++) v[i] *= scale;
        return v;
    }
}
