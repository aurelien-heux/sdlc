package dev.sdlc.agentdesign.domain;

/** A component/module/aggregate description. */
public record DesignElementDraft(String title, String description) {
    public DesignElementDraft {
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("design element title required");
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("design element description required");
    }
}
