package dev.raggateway.rag.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * SHA-256 (64 hex characters) over everything copied into chunks: title, content and the sorted ACL groups
 * (LLD "Indexing worker and the outbox", extended with the title, D-47). Each field is length-prefixed, so no
 * separator inside a field can make two different documents hash alike. t09 stores it; t11 skips a PUT
 * whose hash is unchanged and a job whose hash is no longer the document's.
 */
public final class DocumentHash {

    private DocumentHash() {
    }

    public static String of(String title, String content, Collection<String> aclGroups) {
        MessageDigest sha = sha256();
        field(sha, title);
        field(sha, content);
        for (String group : new TreeSet<>(aclGroups)) field(sha, group);
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void field(MessageDigest sha, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        sha.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
        sha.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
