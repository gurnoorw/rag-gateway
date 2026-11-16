/**
 * Core configuration bound from {@code gateway.*}. Each feature package owns its own
 * {@code @ConfigurationProperties} record with defaults in code (e.g. {@code gateway.rate-limit},
 * {@code gateway.cache}), so features never edit this record.
 */
package dev.raggateway.config;
