package dev.sdlc.agentdesign.domain;

import java.util.List;

public record DesignDraft(List<DesignElementDraft> elements, List<AdrDraft> adrs,
                          List<ApiContractDraft> apiContracts) {
    public DesignDraft {
        elements = List.copyOf(elements);
        adrs = List.copyOf(adrs);
        apiContracts = List.copyOf(apiContracts);
        if (elements.isEmpty() && adrs.isEmpty() && apiContracts.isEmpty())
            throw new IllegalArgumentException("design draft needs at least one artifact");
    }
}
