package dev.sdlc.agentbacklog.bootstrap;

import dev.sdlc.adapter.common.ConsoleHumanInTheLoop;
import dev.sdlc.adapter.common.FileArtifactRepository;
import dev.sdlc.adapter.common.FileBacklogAdapter;
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
import dev.sdlc.agentbacklog.application.BacklogDraftParser;
import dev.sdlc.agentbacklog.application.GenerateBacklogUseCase;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.NodeType;
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
import java.util.Arrays;

@SpringBootApplication
public class AgentBacklogApplication {
    public static void main(String[] args) { SpringApplication.run(AgentBacklogApplication.class, args); }

    @Bean CommandLineRunner demo(ChatModel chatModel, Environment env) {
        return args -> {
            // usage: -Dworkspace=./workspace, args: SPEC-0001 [ADR-0001 DES-0001 ...]
            Path workspace = Path.of(System.getProperty("workspace", "workspace"));

            if (args.length == 0) {
                System.out.println("usage: bootRun --args=\"SPEC-0001 [ADR-0001 DES-0001 ...]\" (-Dworkspace=./workspace)");
                return;
            }
            var specId = ArtifactId.of(args[0]);
            var designIds = Arrays.stream(args).skip(1).map(ArtifactId::of).toList();

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

            var produced = new GenerateBacklogUseCase(new SpringAiLanguageModel(chatModel),
                    graph, repo, new FileBacklogAdapter(repo), bus, trace,
                    new BacklogDraftParser(), "agent-backlog@v1", new Guardrails(5, 1.0))
                    .generate(specId, designIds);

            var hitl = new ConsoleHumanInTheLoop(
                    new BufferedReader(new InputStreamReader(System.in)),
                    System.getProperty("user.name"));
            var approve = new ApproveArtifactUseCase(graph, repo, hitl, Instant::now, gitPort);
            for (var id : produced) {
                var decision = approve.review(id);
                System.out.println(decision.approved()
                        ? id.value() + " APPROVED"
                        : id.value() + " returned to DRAFT: " + decision.feedback());
            }

            // FR-BL-3 stale/orphan report — stale only:
            // ORPHANED listing needs an edge query API — Phase 2
            var staleBacklog = graph.staleNodes().stream()
                    .filter(n -> n.type() == NodeType.BACKLOG_ITEM).toList();
            if (staleBacklog.isEmpty()) {
                System.out.println("backlog stale report: nothing needs revalidation");
            } else {
                System.out.println("backlog stale report (NEEDS_REVALIDATION):");
                for (var n : staleBacklog)
                    System.out.println("  - " + n.id().value() + " — " + n.title());
            }
        };
    }
}
