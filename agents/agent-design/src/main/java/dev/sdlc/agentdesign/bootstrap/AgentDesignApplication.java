package dev.sdlc.agentdesign.bootstrap;

import dev.sdlc.adapter.common.ConsoleHumanInTheLoop;
import dev.sdlc.adapter.common.FileArtifactRepository;
import dev.sdlc.adapter.common.InProcessEventPublisher;
import dev.sdlc.adapter.common.LoggingRunTrace;
import dev.sdlc.adapter.git.GitArtifactRepository;
import dev.sdlc.adapter.git.ProcessGitAdapter;
import dev.sdlc.adapter.git.ProposalScanner;
import dev.sdlc.adapter.graph.PostgresTraceabilityGraph;
import dev.sdlc.adapter.llm.SpringAiLanguageModel;
import dev.sdlc.adapter.otel.OtelRunTrace;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.GitPort;
import dev.sdlc.agent.port.RunTracePort;
import dev.sdlc.agentdesign.application.DesignDraftParser;
import dev.sdlc.agentdesign.application.GenerateDesignUseCase;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.event.ArtifactChanged;
import dev.sdlc.governance.ApproveArtifactUseCase;
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
public class AgentDesignApplication {
    public static void main(String[] args) { SpringApplication.run(AgentDesignApplication.class, args); }

    @Bean CommandLineRunner demo(ChatModel chatModel, Environment env) {
        return args -> {
            // usage: -Dworkspace=./workspace, args: SPEC-0001 [SPEC-0002 ...]
            Path workspace = Path.of(System.getProperty("workspace", "workspace"));

            var specIds = List.of(args).stream().map(ArtifactId::of).toList();
            if (specIds.isEmpty()) {
                System.out.println("usage: bootRun --args=\"SPEC-0001 [SPEC-0002 ...]\" (-Dworkspace=./workspace)");
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

            // repo: plain files default; 'git-approval' profile versions the workspace
            // (selected before rebuild so ProposalScanner can recover pending proposals)
            ArtifactRepositoryPort repo = new FileArtifactRepository(workspace);
            GitPort gitPort = null;
            if (env.acceptsProfiles(Profiles.of("git-approval"))) {
                gitPort = new ProcessGitAdapter(workspace);
                repo = new GitArtifactRepository(repo, gitPort);
            }

            // bus first so rebuild can publish staleness events into it
            // (inline propagation: agent-spec's ArtifactChangedHandler is agent-spec-private)
            var bus = new InProcessEventPublisher();
            bus.subscribe(e -> { if (e instanceof ArtifactChanged c)
                    graph.applyChange(c.subject(), c.newBlobSha()).forEach(bus::publish); });

            new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, graph, bus::publish);
            if (gitPort != null)
                new ProposalScanner(gitPort, new FrontmatterParser()).scanInto(graph);

            // trace: console default; 'otel' profile exports spans
            // (spec §7: without an OTLP endpoint, fall back to console instead of the SDK's
            //  default localhost:4317 OTLP target, which spams export failures)
            if (System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") == null
                    && System.getProperty("otel.traces.exporter") == null)
                System.setProperty("otel.traces.exporter", "console");
            RunTracePort trace = env.acceptsProfiles(Profiles.of("otel"))
                    ? new OtelRunTrace(AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk())
                    : new LoggingRunTrace();

            var hitl = new ConsoleHumanInTheLoop(
                    new BufferedReader(new InputStreamReader(System.in)),
                    System.getProperty("user.name"));
            var useCase = new GenerateDesignUseCase(new SpringAiLanguageModel(chatModel),
                    graph, repo, bus, trace, new DesignDraftParser(),
                    "agent-design@v1", new Guardrails(5, 1.0));
            var approve = new ApproveArtifactUseCase(graph, repo, hitl, Instant::now, gitPort);

            for (var specId : specIds) {
                var produced = useCase.generate(specId);
                for (var id : produced) {
                    var decision = approve.review(id);
                    System.out.println(decision.approved()
                            ? id.value() + " APPROVED"
                            : id.value() + " returned to DRAFT: " + decision.feedback());
                }
            }
        };
    }
}
