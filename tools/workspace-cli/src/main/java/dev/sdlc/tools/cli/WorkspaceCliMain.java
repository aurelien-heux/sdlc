package dev.sdlc.tools.cli;

import java.nio.file.Path;

/** Process entry point; all behaviour lives in the testable {@link WorkspaceCli}. */
public final class WorkspaceCliMain {
    private WorkspaceCliMain() { }

    public static void main(String[] args) {
        System.exit(new WorkspaceCli(
                Path.of(System.getProperty("workspace", "workspace")),
                System.out,
                System.getProperty("user.name")).run(args));
    }
}
