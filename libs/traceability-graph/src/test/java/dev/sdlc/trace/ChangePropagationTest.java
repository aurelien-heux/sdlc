package dev.sdlc.trace;

class ChangePropagationTest extends TraceabilityGraphContract {
    @Override protected TraceabilityGraphPort newGraph() { return new InMemoryTraceabilityGraph(); }
}
