package dev.sdlc.adapter.llm;

import dev.sdlc.agent.port.LanguageModelPort;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 0 adapter: text-only bridge to Spring AI (the spec agent needs no tools yet —
 * its ToolRegistry is empty). Native tool-calling support lands when an agent needs it.
 */
public final class SpringAiLanguageModel implements LanguageModelPort {
    private final ChatModel chatModel;

    public SpringAiLanguageModel(ChatModel chatModel) { this.chatModel = chatModel; }

    @Override public ModelResponse complete(ModelRequest request) {
        // fully-qualified element type: the port's nested Message record shadows Spring AI's
        var messages = new ArrayList<org.springframework.ai.chat.messages.Message>();
        messages.add(new SystemMessage(request.systemPrompt()));
        for (var m : request.messages()) {
            switch (m.role()) {
                case USER, TOOL_RESULT -> messages.add(new UserMessage(m.content()));
                case ASSISTANT -> messages.add(new AssistantMessage(m.content()));
            }
        }
        var response = chatModel.call(new Prompt(messages));
        var usage = response.getMetadata().getUsage();
        long in = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long out = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        // cost stays 0.0 in Phase 0: pricing lookup is a Phase 1 concern (NFR-COST tracks tokens now)
        return new ModelResponse(response.getResult().getOutput().getText(), List.of(),
                new Usage(in, out, 0.0));
    }
}
