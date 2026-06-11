package dev.sdlc.agentspec.bootstrap;

import dev.sdlc.adapter.common.ConsoleHumanInTheLoop;
import dev.sdlc.adapter.common.FileArtifactRepository;
import dev.sdlc.adapter.common.InProcessEventPublisher;
import dev.sdlc.adapter.common.LoggingRunTrace;
import dev.sdlc.adapter.git.GitArtifactRepository;
import dev.sdlc.adapter.git.ProcessGitAdapter;
import dev.sdlc.adapter.graph.PostgresTraceabilityGraph;
import dev.sdlc.adapter.llm.SpringAiLanguageModel;
import dev.sdlc.adapter.otel.OtelRunTrace;
import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.GitPort;
import dev.sdlc.agent.port.RunTracePort;
import dev.sdlc.agent.port.ToolRegistry;
import dev.sdlc.agentspec.application.ApproveArtifactUseCase;
import dev.sdlc.agentspec.application.ArtifactChangedHandler;
import dev.sdlc.agentspec.application.GenerateSpecificationUseCase;
import dev.sdlc.agentspec.application.SpecDraftParser;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.event.ArtifactChanged;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.InMemoryTraceabilityGraph;
import dev.sdlc.trace.ProjectionBuilder;
import dev.sdlc.trace.TraceabilityGraphPort;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@SpringBootApplication
public class AgentSpecApplication {
    public static void main(String[] args) { SpringApplication.run(AgentSpecApplication.class, args); }

    @Bean CommandLineRunner demo(ChatModel chatModel, Environment env) {
        return args -> {
            // usage: -Dworkspace=./workspace, args: REQ-0001 [REQ-0002 ...]
            Path workspace = Path.of(System.getProperty("workspace", "workspace"));

            var reqIds = List.of(args).stream().map(ArtifactId::of).toList();
            if (reqIds.isEmpty()) {
                System.out.println("usage: bootRun --args=\"REQ-0001 [REQ-0002 ...]\" (-Dworkspace=./workspace)");
                return;
            }

            // graph: in-memory default; 'postgres' profile uses the durable projection
            TraceabilityGraphPort graph;
            if (env.acceptsProfiles(Profiles.of("postgres"))) {
                var ds = new PGSimpleDataSource();
                ds.setUrl(System.getenv("SDLC_DB_URL"));
                ds.setUser(System.getenv("SDLC_DB_USER"));
                ds.setPassword(System.getenv("SDLC_DB_PASSWORD"));
                var pg = new PostgresTraceabilityGraph(ds);
                pg.initSchema();
                graph = pg;
            } else {
                graph = new InMemoryTraceabilityGraph();
            }

            new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, graph);

            // repo: plain files default; 'git-approval' profile versions the workspace
            ArtifactRepositoryPort repo = new FileArtifactRepository(workspace);
            GitPort gitPort = null;
            if (env.acceptsProfiles(Profiles.of("git-approval"))) {
                gitPort = new ProcessGitAdapter(workspace);
                repo = new GitArtifactRepository(repo, gitPort);
            }

            var bus = new InProcessEventPublisher();
            bus.subscribe(e -> { if (e instanceof ArtifactChanged c)
                    new ArtifactChangedHandler(graph, bus).on(c); });

            // trace: console default; 'otel' profile exports spans
            RunTracePort trace = env.acceptsProfiles(Profiles.of("otel"))
                    ? new OtelRunTrace(AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk())
                    : new LoggingRunTrace();

            var loop = new AgentLoop(new SpringAiLanguageModel(chatModel),
                    new ToolRegistry(List.of()), trace,
                    new Guardrails(8, 0.50));
            var specId = new GenerateSpecificationUseCase(loop, graph, repo, bus,
                    new SpecDraftParser(), "agent-spec@v1").generate(reqIds);

            var hitl = new ConsoleHumanInTheLoop(
                    new BufferedReader(new InputStreamReader(System.in)),
                    System.getProperty("user.name"));
            var decision = new ApproveArtifactUseCase(graph, repo, hitl, Instant::now, gitPort).review(specId);
            System.out.println(decision.approved()
                    ? specId.value() + " APPROVED — see " + workspace.resolve("specs")
                    : specId.value() + " returned to DRAFT: " + decision.feedback());
        };
    }
}
