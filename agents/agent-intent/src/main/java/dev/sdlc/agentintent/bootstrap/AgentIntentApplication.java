package dev.sdlc.agentintent.bootstrap;

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
import dev.sdlc.agentintent.application.GenerateIntentUseCase;
import dev.sdlc.agentintent.application.IngestInboxUseCase;
import dev.sdlc.agentintent.application.IntentDraftParser;
import dev.sdlc.agentintent.application.ReviewFlagsUseCase;
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

@SpringBootApplication
public class AgentIntentApplication {
    public static void main(String[] args) { SpringApplication.run(AgentIntentApplication.class, args); }

    @Bean CommandLineRunner demo(ChatModel chatModel, Environment env) {
        return args -> {
            // usage: -Dworkspace=./workspace; no args — processes every file in workspace/inbox/
            Path workspace = Path.of(System.getProperty("workspace", "workspace"));

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
            var model = new SpringAiLanguageModel(chatModel);

            var generate = new GenerateIntentUseCase(model, hitl, graph, repo, bus, trace,
                    new IntentDraftParser(), "agent-intent@v1", new Guardrails(12, 1.0));
            var produced = new IngestInboxUseCase(workspace, generate::generate).ingest();
            if (produced.isEmpty()) {
                System.out.println("nothing to ingest — drop .md/.txt files into "
                        + workspace.resolve("inbox"));
                return;
            }

            // advisory dup/conflict pass (FR-INT-2) before the human gate, so flags are in the files
            var flags = new ReviewFlagsUseCase(model, graph, repo, trace,
                    new Guardrails(3, 0.5), "agent-intent@v1").reviewAgainstExisting(produced);
            for (var f : flags)
                System.out.println("[flag] " + f.newId() + " " + f.relation() + " "
                        + f.existingId() + ": " + f.reason());

            var approve = new ApproveArtifactUseCase(graph, repo, hitl, Instant::now, gitPort);
            for (var id : produced) {
                var decision = approve.review(id);
                System.out.println(decision.approved()
                        ? id.value() + " APPROVED"
                        : id.value() + " returned to DRAFT: " + decision.feedback());
            }
        };
    }
}
