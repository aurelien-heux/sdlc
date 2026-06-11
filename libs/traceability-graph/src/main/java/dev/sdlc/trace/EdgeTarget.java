package dev.sdlc.trace;

import dev.sdlc.domain.ArtifactId;
import java.util.regex.Pattern;

/** An upstream reference from frontmatter: target id plus the optionally pinned blob sha. */
public record EdgeTarget(ArtifactId id, String pinnedSha) {
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    public EdgeTarget {
        if (pinnedSha != null && !SHA.matcher(pinnedSha).matches())
            throw new IllegalArgumentException("pinned sha must be 40 hex chars: " + pinnedSha);
    }

    /** Parses "REQ-0012" or "REQ-0012@<sha>". */
    public static EdgeTarget parse(String raw) {
        int at = raw.indexOf('@');
        if (at < 0) return new EdgeTarget(ArtifactId.of(raw), null);
        return new EdgeTarget(ArtifactId.of(raw.substring(0, at)), raw.substring(at + 1));
    }

    public String render() { return pinnedSha == null ? id.value() : id.value() + "@" + pinnedSha; }
}
