/**
 * Immutable domain records shared by every layer (requests, usage, tenants, callers) and the
 * per-request {@link dev.raggateway.core.RequestContext}. No Spring, no I/O in this package.
 */
package dev.raggateway.core;
