package dev.sdlc.agentspec.bootstrap;

import com.sun.net.httpserver.HttpServer;
import dev.sdlc.agent.port.LanguageModelPort;
import dev.sdlc.adapter.llm.SpringAiLanguageModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the openai profile wires a working OpenAI-compatible provider:
 * provider selection via {@code spring.ai.model.chat}, the client honouring a
 * custom {@code base-url} (the Ollama/vLLM/LM Studio compat hook), and the
 * existing adapter mapping text + usage tokens unchanged.
 */
@SpringBootTest(classes = AgentSpecApplication.class,
        properties = "spring.ai.openai.api-key=test-key") // shadows ${OPENAI_API_KEY} from the profile yaml
@ActiveProfiles("openai")
class OpenAiCompatIntegrationTest {

    static final String STUB_COMPLETION = """
            {"id":"x","object":"chat.completion","created":0,"model":"stub",\
            "choices":[{"index":0,"message":{"role":"assistant","content":"stub says hi"},"finish_reason":"stop"}],\
            "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""";

    static final List<String> observedPaths = new CopyOnWriteArrayList<>();

    // static init: the stub is listening before Spring resolves base-url below
    static final HttpServer STUB = startStub();

    static HttpServer startStub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                observedPaths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                byte[] body = STUB_COMPLETION.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void stubEndpoint(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url",
                () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() { STUB.stop(0); }

    @Autowired ChatModel chatModel;

    @Test
    void openAiProfileSelectsOpenAiChatModel() {
        // spring.ai.model.chat=openai must yield exactly one ChatModel: OpenAI's, not Anthropic's
        assertThat(chatModel.getClass().getName()).contains("OpenAi");
    }

    @Test
    void adapterCompletesAgainstOpenAiCompatibleStub() {
        var port = new SpringAiLanguageModel(chatModel);

        var response = port.complete(new LanguageModelPort.ModelRequest(
                "You are a test assistant.",
                List.of(new LanguageModelPort.Message(LanguageModelPort.Role.USER, "say hi")),
                List.of()));

        assertThat(response.finalText()).isEqualTo("stub says hi");
        assertThat(response.usage().inputTokens()).isEqualTo(7);
        assertThat(response.usage().outputTokens()).isEqualTo(3);
        // pin the path the client actually calls so the README's base-url guidance stays honest
        assertThat(observedPaths).contains("POST /v1/chat/completions");
    }
}
