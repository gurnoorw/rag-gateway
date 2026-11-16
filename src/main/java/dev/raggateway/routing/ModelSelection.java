package dev.raggateway.routing;

/**
 * @param model public model name to use
 * @param rule  name of the rule that chose it, or null when the caller named the model
 */
public record ModelSelection(String model, String rule) {
}
