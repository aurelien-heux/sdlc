package dev.sdlc.agent.port;

import java.util.Map;

/** A capability the model may invoke. Implementations are outbound adapters. */
public interface Tool {
    String name();
    String description();
    Map<String, String> parameterSchema();
    String execute(Map<String, Object> args);
}
