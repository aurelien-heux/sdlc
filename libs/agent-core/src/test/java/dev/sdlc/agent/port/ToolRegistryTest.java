package dev.sdlc.agent.port;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ToolRegistryTest {
    Tool echo = new Tool() {
        public String name() { return "echo"; }
        public String description() { return "echoes input"; }
        public Map<String, String> parameterSchema() { return Map.of("text", "string"); }
        public String execute(Map<String, Object> args) { return String.valueOf(args.get("text")); }
    };

    @Test
    void executesRegisteredTool() {
        var registry = new ToolRegistry(java.util.List.of(echo));
        assertThat(registry.execute("echo", Map.of("text", "hi"))).isEqualTo("hi");
        assertThat(registry.schemas()).hasSize(1);
    }

    @Test
    void unknownToolIsAnError() {
        var registry = new ToolRegistry(java.util.List.of());
        assertThatThrownBy(() -> registry.execute("rm-rf", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allow-listed");
    }
}
