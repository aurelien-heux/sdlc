package dev.sdlc.agenttestgen.bootstrap;

import dev.sdlc.adapter.common.ConsoleHumanInTheLoop;
import dev.sdlc.adapter.common.FileArtifactRepository;
import dev.sdlc.adapter.common.InProcessEventPublisher;
import dev.sdlc.adapter.common.LoggingRunTrace;
import dev.sdlc.adapter.common.ProcessTestRunner;
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
import dev.sdlc.agenttestgen.application.GenerateTestsUseCase;
import dev.sdlc.agenttestgen.application.PlaceAndRunUseCase;
import dev.sdlc.agenttestgen.application.StepSkeletonParser;
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
public class AgentTestgenApplication {
    public static void main(String[] args) { SpringApplication.run(AgentTestgenApplication.class, args); }

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
            // skeleton language is CONFIG, not a model choice (FR-TEST-3: default Java/Cucumber)
            var language = env.getProperty("SDLC_TEST_LANGUAGE", "java");
            var useCase = new GenerateTestsUseCase(new SpringAiLanguageModel(chatModel),
                    graph, repo, bus, trace, new StepSkeletonParser(),
                    "agent-testgen@v1", new Guardrails(5, 1.0), language);
            var approve = new ApproveArtifactUseCase(graph, repo, hitl, Instant::now, gitPort);

            // 'target-repo' profile (FR-TEST-4): place + run against a configured repository.
            // The command is allow-listed from SDLC_TEST_CMD — the model never composes it.
            PlaceAndRunUseCase placeAndRun = null;
            Path targetRepo = null;
            String featuresDir = null, stepsDir = null;
            List<String> command = null;
            if (env.acceptsProfiles(Profiles.of("target-repo"))) {
                var target = env.getProperty("SDLC_TARGET_REPO");
                if (target == null || target.isBlank())
                    throw new IllegalStateException("the 'target-repo' profile requires SDLC_TARGET_REPO");
                targetRepo = Path.of(target);
                featuresDir = env.getProperty("SDLC_FEATURES_DIR", "src/test/resources/features");
                stepsDir = env.getProperty("SDLC_STEPS_DIR", "src/test/java");
                command = List.of(env.getProperty("SDLC_TEST_CMD", "./gradlew test").split(" "));
                placeAndRun = new PlaceAndRunUseCase(graph, repo, targetRepo, featuresDir,
                        stepsDir, new ProcessTestRunner(targetRepo, command), Instant::now);
            }

            for (var specId : specIds) {
                var produced = useCase.generate(specId);
                for (var id : produced) {
                    var decision = approve.review(id);
                    System.out.println(decision.approved()
                            ? id.value() + " APPROVED"
                            : id.value() + " returned to DRAFT: " + decision.feedback());
                }
                if (placeAndRun != null) {
                    var featureId = produced.get(0);
                    var stepsId = produced.get(1);
                    // spec §6 risk note: writing into a user repo — say what goes where
                    // BEFORE the placement + configured command run
                    System.out.println("target-repo: placing " + featureId.value() + " under "
                            + targetRepo.resolve(featuresDir) + " and " + stepsId.value()
                            + " under " + targetRepo.resolve(stepsDir)
                            + ", then running: " + String.join(" ", command));
                    var result = placeAndRun.placeAndRun(featureId, stepsId);
                    System.out.println("test run " + (result.passed() ? "PASSED" : "FAILED"));
                    System.out.println(result.outputTail());
                }
            }
        };
    }
}
