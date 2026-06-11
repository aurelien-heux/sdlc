package dev.sdlc.agentdesign.domain;

/** An endpoint/message contract, body in markdown. */
public record ApiContractDraft(String title, String contract) {
    public ApiContractDraft {
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("api contract title required");
        if (contract == null || contract.isBlank())
            throw new IllegalArgumentException("api contract body required");
    }
}
