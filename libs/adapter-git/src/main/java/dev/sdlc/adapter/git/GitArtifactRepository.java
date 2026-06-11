package dev.sdlc.adapter.git;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.GitPort;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * FR-SAC-2 (local-first): artifact writes land as commits on proposal/<artifactId>;
 * approval merges that branch into main (done by the approval use case via GitPort).
 * Non-artifact paths (inbox, notes) commit straight to main.
 */
public final class GitArtifactRepository implements ArtifactRepositoryPort {
    private static final Pattern ARTIFACT_FILE = Pattern.compile("([A-Z]{2,}-\\d{4})\\.md");

    private final ArtifactRepositoryPort files;
    private final GitPort git;

    public GitArtifactRepository(ArtifactRepositoryPort files, GitPort git) {
        this.files = files; this.git = git;
    }

    public GitPort git() { return git; }

    @Override public String write(String repoPath, String content) {
        var artifactId = artifactIdFrom(repoPath);
        if (artifactId == null) {
            var sha = files.write(repoPath, content);
            git.commitAll("update: " + repoPath);
            return sha;
        }
        String branch = "proposal/" + artifactId;
        // Use branchExists to avoid recreating the branch from main (which would orphan v1)
        git.checkoutBranch(branch, !git.branchExists(branch));
        try {
            var sha = files.write(repoPath, content);
            git.commitAll("proposal: " + artifactId);
            return sha;
        } finally {
            git.checkoutBranch("main", false);
        }
    }

    @Override public Optional<String> read(String repoPath) {
        var onMain = files.read(repoPath);
        if (onMain.isPresent()) return onMain;
        var artifactId = artifactIdFrom(repoPath);
        return artifactId == null ? Optional.empty()
                : git.showFile("proposal/" + artifactId, repoPath);
    }

    private static String artifactIdFrom(String repoPath) {
        var name = repoPath.substring(repoPath.lastIndexOf('/') + 1);
        var m = ARTIFACT_FILE.matcher(name);
        return m.matches() ? m.group(1) : null;
    }
}
