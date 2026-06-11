package dev.sdlc.agentintent.application;

import dev.sdlc.agent.port.HumanInTheLoopPort;
import dev.sdlc.agent.port.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** FR-INT-4 / FR-HITL-2: the model asks, the human answers; every exchange is recorded. */
public final class AskHumanTool implements Tool {
    private final HumanInTheLoopPort human;
    private final List<String> transcript = new ArrayList<>();

    public AskHumanTool(HumanInTheLoopPort human) { this.human = human; }

    @Override public String name() { return "ask_human"; }
    @Override public String description() {
        return "Ask the stakeholder ONE clarifying question when the source is materially ambiguous.";
    }
    @Override public Map<String, String> parameterSchema() { return Map.of("question", "string"); }

    @Override public String execute(Map<String, Object> args) {
        var question = String.valueOf(args.get("question"));
        var answer = human.askClarifyingQuestion(question);
        transcript.add("Q: " + question + "\nA: " + answer);
        return answer;
    }

    /** Q/A pairs asked during this run, in order. */
    public List<String> transcript() { return List.copyOf(transcript); }
}
