package dev.sdlc.agenttestgen.domain;

import java.util.Set;

/** LLM-generated binding glue; a review draft, never promised-compiling code. */
public record StepSkeletonDraft(String language, String content) {
    private static final Set<String> LANGUAGES = Set.of("java", "kotlin", "typescript", "python");

    public StepSkeletonDraft {
        if (!LANGUAGES.contains(language))
            throw new IllegalArgumentException("language must be one of " + LANGUAGES + ": " + language);
        if (content == null || content.isBlank())
            throw new IllegalArgumentException("skeleton content required");
    }
}
