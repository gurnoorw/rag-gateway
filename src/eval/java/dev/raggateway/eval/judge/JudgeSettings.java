package dev.raggateway.eval.judge;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Answer-judge settings read from {@code eval/judge/judge.properties} (D-24, D-53). Nothing here has a default in
 * code: the model id, prices, effort, max tokens and cap come from the file, so a price change is a one-line
 * edit with its source next to it, and the code cannot drift from what the file says.
 *
 * <p>The key variable is fixed to {@link #API_KEY_ENV}: the file names it so a reader sees where the key comes
 * from, but a file that names another variable (for example the gateway's {@code GATEWAY_CLAUDE_API_KEY}) is
 * refused, so the gateway's provider key can never be used by accident.
 *
 * @param model                  Claude model id, e.g. {@code claude-haiku-5-5}
 * @param inputUsdPerMtok        list price per million input tokens
 * @param outputUsdPerMtok       list price per million output tokens
 * @param priceSource            where the prices were read (copied into the results file)
 * @param effort                 {@code output_config.effort}: low, medium or high (the results schema's enum)
 * @param maxTokens              {@code max_tokens}; adaptive thinking counts toward it, so it is also the
 *                               worst-case output per call for the spend cap
 * @param capUsd                 hard spend cap per run in USD
 * @param apiKeyEnv              environment variable holding the key (always {@link #API_KEY_ENV})
 */
public record JudgeSettings(String model, BigDecimal inputUsdPerMtok, BigDecimal outputUsdPerMtok, String priceSource,
                            String effort, long maxTokens, BigDecimal capUsd, String apiKeyEnv) {

    /** The only variable the judge key is read from (D-53). */
    public static final String API_KEY_ENV = "EVAL_JUDGE_API_KEY";
    /** Default location, relative to the repository root. */
    public static final Path DEFAULT_FILE = Path.of("eval/judge/judge.properties");

    private static final Set<String> EFFORTS = Set.of("low", "medium", "high");

    public JudgeSettings {
        Objects.requireNonNull(model, "model");
        if (!model.startsWith("claude-")) throw new IllegalArgumentException("model must be a Claude model id");
        requireNonNegative(inputUsdPerMtok, "price.input-usd-per-mtok");
        requireNonNegative(outputUsdPerMtok, "price.output-usd-per-mtok");
        if (priceSource == null || priceSource.isBlank()) throw new IllegalArgumentException("price.source is required");
        if (!EFFORTS.contains(effort)) throw new IllegalArgumentException("effort must be one of " + EFFORTS);
        if (maxTokens <= 0) throw new IllegalArgumentException("max-tokens must be positive");
        Objects.requireNonNull(capUsd, "cap-usd");
        if (capUsd.signum() <= 0) throw new IllegalArgumentException("cap-usd must be positive");
        if (!API_KEY_ENV.equals(apiKeyEnv)) {
            throw new IllegalArgumentException("api-key-env must be " + API_KEY_ENV
                    + ": the judge never reads another variable, so the gateway's key cannot be picked up");
        }
    }

    /** Reads and checks the properties file. Every key is required. */
    public static JudgeSettings load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return new JudgeSettings(
                required(p, "model"),
                new BigDecimal(required(p, "price.input-usd-per-mtok")),
                new BigDecimal(required(p, "price.output-usd-per-mtok")),
                required(p, "price.source"),
                required(p, "effort"),
                Long.parseLong(required(p, "max-tokens")),
                new BigDecimal(required(p, "cap-usd")),
                required(p, "api-key-env"));
    }

    /**
     * The same settings with a lower cap (the command line's {@code --cap-usd}). Raising the cap above the file is
     * refused: that takes an edit to the file, which is reviewed.
     */
    public JudgeSettings withCap(BigDecimal lower) {
        Objects.requireNonNull(lower, "cap");
        if (lower.signum() <= 0) throw new IllegalArgumentException("--cap-usd must be positive");
        if (lower.compareTo(capUsd) > 0) {
            throw new IllegalArgumentException("--cap-usd " + lower.toPlainString() + " is above the file's cap "
                    + capUsd.toPlainString() + "; raise it in judge.properties, not on the command line");
        }
        return new JudgeSettings(model, inputUsdPerMtok, outputUsdPerMtok, priceSource, effort, maxTokens, lower,
                apiKeyEnv);
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("judge.properties: '" + key + "' is required");
        return v.trim();
    }

    private static void requireNonNegative(BigDecimal v, String name) {
        Objects.requireNonNull(v, name);
        if (v.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
    }
}
