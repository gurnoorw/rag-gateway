package dev.raggateway.eval.judge;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * Hard spend cap for one judge run (D-53), enforced in code around every call.
 *
 * <ul>
 *   <li><b>Before a call:</b> its worst case, estimated input tokens × input price + {@code max_tokens} × output
 *       price, must fit in what is left ({@link #allows}). If it doesn't, the call is not made and the run stops.
 *       {@code max_tokens} is the true ceiling on output (thinking counts toward it), so only the input side is
 *       an estimate.</li>
 *   <li><b>After a call:</b> the reported usage is added ({@link #charge}). A failed call whose usage is unknown
 *       is charged its worst case ({@link #chargeWorstCase}): it may have been billed, and over-counting only
 *       stops the run early.</li>
 * </ul>
 * Money is {@link BigDecimal} so the comparison with the cap is exact. Not thread-safe: calls are sequential.
 */
public final class SpendCap {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final BigDecimal capUsd;
    private final BigDecimal inputUsdPerMtok;
    private final BigDecimal outputUsdPerMtok;
    private final long maxOutputTokens;
    private BigDecimal spentUsd = BigDecimal.ZERO;

    public SpendCap(JudgeSettings settings) {
        this(settings.capUsd(), settings.inputUsdPerMtok(), settings.outputUsdPerMtok(), settings.maxTokens());
    }

    SpendCap(BigDecimal capUsd, BigDecimal inputUsdPerMtok, BigDecimal outputUsdPerMtok, long maxOutputTokens) {
        this.capUsd = Objects.requireNonNull(capUsd, "capUsd");
        this.inputUsdPerMtok = Objects.requireNonNull(inputUsdPerMtok, "inputUsdPerMtok");
        this.outputUsdPerMtok = Objects.requireNonNull(outputUsdPerMtok, "outputUsdPerMtok");
        if (maxOutputTokens <= 0) throw new IllegalArgumentException("maxOutputTokens must be positive");
        this.maxOutputTokens = maxOutputTokens;
    }

    /** Cost of a call with these token counts at the configured prices. */
    public BigDecimal cost(long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("token counts must be >= 0");
        BigDecimal in = BigDecimal.valueOf(inputTokens).multiply(inputUsdPerMtok);
        BigDecimal out = BigDecimal.valueOf(outputTokens).multiply(outputUsdPerMtok);
        return in.add(out).divide(MILLION, MathContext.DECIMAL64);
    }

    /** Worst case of one call: the estimated input plus {@code max_tokens} of output. */
    public BigDecimal worstCase(long estimatedInputTokens) {
        return cost(estimatedInputTokens, maxOutputTokens);
    }

    /** True when a call with this input estimate can be made without the cap being exceeded in the worst case. */
    public boolean allows(long estimatedInputTokens) {
        return spentUsd.add(worstCase(estimatedInputTokens)).compareTo(capUsd) <= 0;
    }

    /** Adds the usage a completed call reported. */
    public void charge(long inputTokens, long outputTokens) {
        spentUsd = spentUsd.add(cost(inputTokens, outputTokens));
    }

    /** Adds a failed call's worst case (its usage is unknown). */
    public void chargeWorstCase(long estimatedInputTokens) {
        spentUsd = spentUsd.add(worstCase(estimatedInputTokens));
    }

    public BigDecimal spentUsd() {
        return spentUsd;
    }

    public BigDecimal capUsd() {
        return capUsd;
    }

    public BigDecimal remainingUsd() {
        return capUsd.subtract(spentUsd).max(BigDecimal.ZERO);
    }
}
