package dev.raggateway.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Prometheus text exposition format of {@code /actuator/prometheus} (t04 round prep), so tests can
 * assert on metric values and label names instead of grepping text: AC-t04-1/2 (counter values), AC-t04-3
 * (label allow-list), AC-t04-6 (every dashboard metric exists), AC-t04-7 (histogram buckets).
 *
 * <p>Handles the subset Micrometer's Prometheus registry writes: {@code # HELP}/{@code # TYPE} comments, one
 * sample per line, {@code name{label="value",...} value}, escaped quotes and backslashes in values. Exemplars
 * and timestamps are not produced by this application and are not parsed.
 */
public final class PrometheusScrape {

    /** One sample line. */
    public record Sample(String name, Map<String, String> labels, double value) {
        public String label(String key) {
            return labels.get(key);
        }
    }

    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{(.*)})?\\s+(\\S+)$");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final List<Sample> samples;

    private PrometheusScrape(List<Sample> samples) {
        this.samples = List.copyOf(samples);
    }

    /** Fetches and parses {@code <baseUrl>/actuator/prometheus}. */
    public static PrometheusScrape fetch(String baseUrl) {
        try {
            var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/prometheus")).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("scrape returned HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("scrape failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    public static PrometheusScrape parse(String text) {
        List<Sample> samples = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            Matcher m = SAMPLE.matcher(line.trim());
            if (!m.matches()) throw new IllegalArgumentException("not a sample line: " + line);
            Map<String, String> labels = new LinkedHashMap<>();
            if (m.group(3) != null) {
                Matcher l = LABEL.matcher(m.group(3));
                while (l.find()) labels.put(l.group(1), unescape(l.group(2)));
            }
            samples.add(new Sample(m.group(1), labels, parseValue(m.group(4))));
        }
        return new PrometheusScrape(samples);
    }

    public List<Sample> samples() {
        return samples;
    }

    /** Samples of one series name (e.g. {@code gateway_requests_total}). */
    public List<Sample> series(String name) {
        return samples.stream().filter(s -> s.name().equals(name)).toList();
    }

    /** Sum of the values of {@code name} whose labels contain every given key=value pair. */
    public double sum(String name, Map<String, String> labelFilter) {
        return series(name).stream()
                .filter(s -> labelFilter.entrySet().stream().allMatch(e -> e.getValue().equals(s.labels().get(e.getKey()))))
                .mapToDouble(Sample::value).sum();
    }

    /** Every distinct series name. */
    public Set<String> names() {
        return new TreeSet<>(samples.stream().map(Sample::name).toList());
    }

    /**
     * True if a metric family is exposed, whatever suffix Prometheus added: {@code gateway_requests} matches
     * {@code gateway_requests_total}; a timer matches its {@code _seconds_bucket/_count/_sum/_max} series.
     */
    public boolean hasFamily(String familyPrefix) {
        return samples.stream().anyMatch(s -> s.name().equals(familyPrefix) || s.name().startsWith(familyPrefix + "_"));
    }

    /** Label names used by the series selected by {@code nameFilter}. */
    public Set<String> labelNames(Predicate<String> nameFilter) {
        Set<String> names = new TreeSet<>();
        samples.stream().filter(s -> nameFilter.test(s.name())).forEach(s -> names.addAll(s.labels().keySet()));
        return names;
    }

    /** Converts a Micrometer dotted name to its Prometheus base name ({@code gateway.request.ttft} → {@code gateway_request_ttft}). */
    public static String prometheusBase(String micrometerName) {
        return micrometerName.replace('.', '_');
    }

    private static double parseValue(String v) {
        return switch (v) {
            case "+Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            case "NaN" -> Double.NaN;
            default -> Double.parseDouble(v);
        };
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }
}
