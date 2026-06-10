package dev.sdlc.agent.port;

import java.util.List;
import java.util.Map;

/** Outbound port to the LLM. The provider SDK lives in an adapter, never here. */
public interface LanguageModelPort {

    ModelResponse complete(ModelRequest request);

    record ModelRequest(String systemPrompt, List<Message> messages, List<ToolSchema> tools) {}
    record Message(Role role, String content) {}
    enum Role { USER, ASSISTANT, TOOL_RESULT }
    record ToolSchema(String name, String description, Map<String, String> parameters) {}

    /** Either finalText is set (model finished) or toolCalls is non-empty (model wants tools). */
    record ModelResponse(String finalText, List<ToolCall> toolCalls, Usage usage) {
        public boolean wantsTools() { return toolCalls != null && !toolCalls.isEmpty(); }
    }
    record ToolCall(String toolName, Map<String, Object> arguments) {}
    record Usage(long inputTokens, long outputTokens, double costUsd) {}
}
