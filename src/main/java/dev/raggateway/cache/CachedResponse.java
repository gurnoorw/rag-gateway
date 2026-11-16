package dev.raggateway.cache;

import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Usage;

/**
 * A stored answer. Replayed as SSE or JSON by the caller's {@code stream} flag; a hit reports zero
 * provider usage to the client and the log (AC-t05-10). {@code originalUsage} is kept for diagnostics.
 */
public record CachedResponse(String content, FinishReason finishReason, String model, String provider,
                             Usage originalUsage) {
}
