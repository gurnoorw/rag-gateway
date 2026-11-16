package dev.raggateway.core;

import java.util.Objects;

/** One chat message. Text content only (images and tool calls are out of scope, ROADMAP). */
public record Message(Role role, String content) {
    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
