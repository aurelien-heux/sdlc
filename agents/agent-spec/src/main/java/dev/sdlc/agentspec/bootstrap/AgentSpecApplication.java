package dev.sdlc.agentspec.bootstrap;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.ToolRegistry;
import dev.sdlc.agentspec.adapters.outbound.ConsoleHumanInTheLoop;
import dev.sdlc.agentspec.adapters.outbound.FileArtifactRepository;
import dev.sdlc.agentspec.adapters.outbound.InProcessEventPublisher;
import dev.sdlc.agentspec.adapters.outbound.LoggingRunTrace;
import dev.sdlc.agentspec.adapters.outbound.SpringAiLanguageModel;
import dev.sdlc.agentspec.application.ApproveArtifactUseCase;
import dev.sdlc.agentspec.application.ArtifactChangedHandler;
import dev.sdlc.agentspec.application.GenerateSpecificationUseCase;
import dev.sdlc.agentspec.application.SpecDraftParser;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.event.ArtifactChanged;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.InMemoryTraceabilityGraph;
import dev.sdlc.trace.ProjectionBuilder;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@SpringBootApplication
public class AgentSpecApplication {
    public static void main(String[] args) { SpringApplication.run(AgentSpecApplication.class, args); }

    @Bean CommandLineRunner demo(ChatModel chatModel) {
        return args -> {
            // usage: --workspace=./workspace REQ-0001 [REQ-0002 ...]
            Path workspace = Path.of(System.getProperty("workspace", "workspace"));
            var graph = new InMemoryTraceabilityGraph();
            new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, graph);
            var repo = new FileArtifactRepository(workspace);
            var bus = new InProcessEventPublisher();
            bus.subscribe(e -> { if (e instanceof ArtifactChanged c)
                    new ArtifactChangedHandler(graph, bus).on(c); });
            var loop = new AgentLoop(new SpringAiLanguageModel(chatModel),
                    new ToolRegistry(List.of()), new LoggingRunTrace(),
                    new Guardrails(8, 0.50));

            var reqIds = List.of(args).stream().map(ArtifactId::of).toList();
            var specId = new GenerateSpecificationUseCase(loop, graph, repo, bus,
                    new SpecDraftParser(), "agent-spec@v1").generate(reqIds);

            var hitl = new ConsoleHumanInTheLoop(
                    new BufferedReader(new InputStreamReader(System.in)),
                    System.getProperty("user.name"));
            var decision = new ApproveArtifactUseCase(graph, repo, hitl, Instant::now).review(specId);
            System.out.println(decision.approved()
                    ? specId.value() + " APPROVED — see " + workspace.resolve("specs")
                    : specId.value() + " returned to DRAFT: " + decision.feedback());
        };
    }
}
