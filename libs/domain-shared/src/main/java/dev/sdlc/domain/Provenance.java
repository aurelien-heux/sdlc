package dev.sdlc.domain;

import java.time.Instant;
import java.util.List;

/** Anti-hallucination metadata carried by every node (brief §12.5). */
public record Provenance(
        List<String> sourceRefs,
        String generatedBy,
        double confidence,
        List<String> assumptions,
        boolean humanApproved,
        String approvedBy,
        Instant approvedAt) {

    public Provenance {
        sourceRefs = List.copyOf(sourceRefs);
        assumptions = List.copyOf(assumptions);
        if (sourceRefs.isEmpty() && assumptions.isEmpty())
            throw new IllegalArgumentException("artifact must be grounded: sourceRefs or assumptions required");
        if (humanApproved && approvedBy == null)
            throw new IllegalArgumentException("humanApproved requires approvedBy");
        if (!(confidence >= 0.0 && confidence <= 1.0))
            throw new IllegalArgumentException("confidence must be in [0,1]: " + confidence);
    }

    public static Provenance generated(List<String> sourceRefs, String generatedBy,
                                       double confidence, List<String> assumptions) {
        return new Provenance(sourceRefs, generatedBy, confidence, assumptions, false, null, null);
    }

    public Provenance approve(String approver, Instant at) {
        return new Provenance(sourceRefs, generatedBy, confidence, assumptions, true, approver, at);
    }
}
