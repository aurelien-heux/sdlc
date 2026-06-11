package dev.sdlc.agentdesign.domain;

import java.util.List;

/** FR-DES-3: a decision is only recordable alongside the alternatives it beat. */
public record AdrDraft(String title, String context, String decision,
                       List<Alternative> alternatives, List<String> consequences) {
    public record Alternative(String option, String tradeoff) {
        public Alternative {
            if (option == null || option.isBlank())
                throw new IllegalArgumentException("alternative option required");
        }
    }

    public AdrDraft {
        alternatives = List.copyOf(alternatives);
        consequences = List.copyOf(consequences);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("adr title required");
        if (decision == null || decision.isBlank())
            throw new IllegalArgumentException("adr decision required");
        if (alternatives.size() < 2)
            throw new IllegalArgumentException("adr needs >= 2 considered alternatives, got "
                    + alternatives.size());
    }
}
