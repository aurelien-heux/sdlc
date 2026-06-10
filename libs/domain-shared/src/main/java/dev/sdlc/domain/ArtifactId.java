package dev.sdlc.domain;

import java.util.regex.Pattern;

/** Stable logical identity, e.g. REQ-0012. Identity is separate from version (blobSha). */
public record ArtifactId(String value) {
    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2,}-\\d{4}");

    public ArtifactId {
        if (value == null)
            throw new IllegalArgumentException("ArtifactId must not be null");
        if (!FORMAT.matcher(value).matches())
            throw new IllegalArgumentException("ArtifactId must match PREFIX-#### : " + value);
    }

    public static ArtifactId of(String value) { return new ArtifactId(value); }

    public String prefix() { return value.substring(0, value.indexOf('-')); }

    @Override public String toString() { return value; }
}
