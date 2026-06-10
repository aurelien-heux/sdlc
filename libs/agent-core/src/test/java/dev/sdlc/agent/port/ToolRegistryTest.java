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

    @Test
    void schemasPreserveRegistrationOrder() {
        Tool a = namedTool("alpha"); Tool b = namedTool("beta"); Tool c = namedTool("gamma");
        var registry = new ToolRegistry(java.util.List.of(c, a, b));
        assertThat(registry.schemas()).extracting(LanguageModelPort.ToolSchema::name)
                .containsExactly("gamma", "alpha", "beta");
    }

    @Test
    void duplicateToolNamesAreRejectedWithDomainMessage() {
        assertThatThrownBy(() -> new ToolRegistry(java.util.List.of(namedTool("echo"), namedTool("echo"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate tool name: echo");
    }

    private Tool namedTool(String name) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return name; }
            public Map<String, String> parameterSchema() { return Map.of(); }
            public String execute(Map<String, Object> args) { return name; }
        };
    }
}
