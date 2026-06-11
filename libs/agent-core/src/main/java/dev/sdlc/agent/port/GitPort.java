package dev.sdlc.agent.port;

import java.util.Optional;

/** Outbound port to version control for the ARTIFACT workspace (not the source repo). */
public interface GitPort {
    /** Initializes the repo (with an initial commit on `main`) if absent; idempotent. */
    void ensureRepo();
    boolean branchExists(String name);
    void checkoutBranch(String name, boolean createFromMain);
    void commitAll(String message);
    void merge(String branch, String message);
    String currentBranch();
    Optional<String> showFile(String branch, String relativePath);
}
