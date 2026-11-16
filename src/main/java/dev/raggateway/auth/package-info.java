/**
 * API-key authentication at the HTTP edge (DECISIONS.md D-14, C16, D-22). Keys are stored only as
 * SHA-256 hashes; the presented key is hashed, looked up, and discarded.
 */
package dev.raggateway.auth;
