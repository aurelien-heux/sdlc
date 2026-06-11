package dev.sdlc.agent;

import java.util.Optional;

/**
 * Per-run context carried via ScopedValue (Java 25) inside agent-core. Ports keep explicit
 * runId parameters — this exists so tools/adapters deep in a run can correlate without
 * threading runId through every signature.
 */
public record RunContext(String runId) {
    static final ScopedValue<RunContext> SCOPE = ScopedValue.newInstance();

    public static Optional<RunContext> current() {
        return SCOPE.isBound() ? Optional.of(SCOPE.get()) : Optional.empty();
    }
}
