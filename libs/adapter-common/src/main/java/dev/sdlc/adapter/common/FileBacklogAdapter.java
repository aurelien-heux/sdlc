package dev.sdlc.adapter.common;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.BacklogPort;
import dev.sdlc.domain.ArtifactId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Spec §11: backlog items ARE graph artifacts; this port impl just owns their file layout. */
public final class FileBacklogAdapter implements BacklogPort {
    private final ArtifactRepositoryPort repo;
    private final List<ArtifactId> open = new ArrayList<>();

    public FileBacklogAdapter(ArtifactRepositoryPort repo) { this.repo = repo; }

    // The port is vendor-shaped: level/title/estimate exist for vendor adapters (Jira needs
    // fields, not files). This file adapter ignores them — body arrives as the FULL artifact
    // file content (frontmatter included); the use case renders it, this adapter owns only
    // placement. externalRef = the path (vendor id later).
    @Override public String upsert(ArtifactId id, String level, String title, String body, String estimate) {
        var path = "backlog/" + id.value() + ".md";
        repo.write(path, body);
        if (!open.contains(id)) open.add(id);
        return path;
    }

    @Override public Optional<String> find(ArtifactId id) {
        return repo.read("backlog/" + id.value() + ".md").map(c -> "backlog/" + id.value() + ".md");
    }

    @Override public List<ArtifactId> listOpen() { return List.copyOf(open); }
}
