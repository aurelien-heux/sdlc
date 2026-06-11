package dev.sdlc.adapter.git;

import dev.sdlc.agent.port.GitPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** GitPort over the git CLI (no JGit). All commands run with -C <workdir>. */
public final class ProcessGitAdapter implements GitPort {
    private final Path workdir;

    public ProcessGitAdapter(Path workdir) {
        this.workdir = workdir;
        ensureRepo();
    }

    @Override public void ensureRepo() {
        if (workdir.resolve(".git").toFile().exists()) return;
        run("init", "--initial-branch=main");
        run("config", "user.email", "agents@sdlc.local");
        run("config", "user.name", "sdlc-agent");
        run("commit", "--allow-empty", "-m", "workspace init");
    }

    @Override public boolean branchExists(String name) {
        return !run("branch", "--list", name).isBlank();
    }

    @Override public void checkoutBranch(String name, boolean createFromMain) {
        if (createFromMain) run("checkout", "-B", name, "main");
        else run("checkout", name);
    }

    @Override public void commitAll(String message) {
        run("add", "-A");
        // --allow-empty: re-writes with identical content must not fail the flow
        run("commit", "--allow-empty", "-m", message);
    }

    @Override public void merge(String branch, String message) {
        run("checkout", "main");
        run("merge", "--no-ff", branch, "-m", message);
    }

    @Override public String currentBranch() {
        return run("rev-parse", "--abbrev-ref", "HEAD").strip();
    }

    @Override public Optional<String> showFile(String branch, String relativePath) {
        try {
            return Optional.of(run("show", branch + ":" + relativePath));
        } catch (IllegalStateException e) {
            return Optional.empty(); // path not on that branch
        }
    }

    private String run(String... args) {
        var cmd = new ArrayList<String>(List.of("git", "-C", workdir.toString()));
        cmd.addAll(List.of(args));
        try {
            var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0)
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("git unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running git", e);
        }
    }
}
