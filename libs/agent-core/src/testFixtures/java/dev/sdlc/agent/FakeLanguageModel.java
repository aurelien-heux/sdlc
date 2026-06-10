package dev.sdlc.agent;

import dev.sdlc.agent.port.LanguageModelPort;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Scripted model: returns queued responses in order; records every request. */
public final class FakeLanguageModel implements LanguageModelPort {
    private final Deque<ModelResponse> script = new ArrayDeque<>();
    public final List<ModelRequest> requests = new ArrayList<>();

    public FakeLanguageModel respondWith(ModelResponse... responses) {
        script.addAll(List.of(responses));
        return this;
    }

    @Override public ModelResponse complete(ModelRequest request) {
        requests.add(request);
        if (script.isEmpty()) throw new AssertionError("model called more times than scripted");
        return script.poll();
    }

    public static ModelResponse finalText(String text) {
        return new ModelResponse(text, List.of(), new Usage(100, 50, 0.001));
    }
    public static ModelResponse toolCall(String tool, java.util.Map<String, Object> args) {
        return new ModelResponse(null, List.of(new ToolCall(tool, args)), new Usage(100, 20, 0.001));
    }
}
