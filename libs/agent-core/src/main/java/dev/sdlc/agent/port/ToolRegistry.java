package dev.sdlc.agent.port;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Per-agent allow-list of tools (NFR-SEC); the loop executes only through this. */
public final class ToolRegistry {
    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> tools) {
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(Tool::name, Function.identity()));
    }

    public List<LanguageModelPort.ToolSchema> schemas() {
        return tools.values().stream()
                .map(t -> new LanguageModelPort.ToolSchema(t.name(), t.description(), t.parameterSchema()))
                .toList();
    }

    public String execute(String name, Map<String, Object> args) {
        var tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("tool not allow-listed: " + name);
        return tool.execute(args);
    }
}
