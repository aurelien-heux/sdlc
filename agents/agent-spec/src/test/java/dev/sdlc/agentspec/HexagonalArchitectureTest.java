package dev.sdlc.agentspec;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "dev.sdlc")
class HexagonalArchitectureTest {

    private static final String[] FRAMEWORK_PACKAGES = {
            "org.springframework..", "org.springframework.ai..", "dev.langchain4j..",
            "io.opentelemetry..", "org.postgresql..", "org.yaml.."};

    // any agent's domain: no app/adapters/bootstrap/framework deps
    @ArchTest
    static final ArchRule agentDomainsAreInnermost = noClasses()
            .that().resideInAPackage("dev.sdlc.agent*.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(merge(
                    new String[]{"..application..", "..adapters..", "..bootstrap.."},
                    FRAMEWORK_PACKAGES));

    // any agent's application: ports only — no adapters/bootstrap/framework
    @ArchTest
    static final ArchRule agentApplicationsUsePortsOnly = noClasses()
            .that().resideInAPackage("dev.sdlc.agent*.application..")
            .should().dependOnClassesThat().resideInAnyPackage(merge(
                    new String[]{"..adapters..", "..bootstrap.."},
                    FRAMEWORK_PACKAGES));

    // core libs stay framework-free (snakeyaml is the one sanctioned exception in dev.sdlc.trace)
    @ArchTest
    static final ArchRule coreLibsAreFrameworkFree = noClasses()
            .that().resideInAnyPackage("dev.sdlc.domain..", "dev.sdlc.agent.port..", "dev.sdlc.agent", "dev.sdlc.trace..", "dev.sdlc.governance..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "org.springframework.ai..", "dev.langchain4j..",
                    "io.opentelemetry..", "org.postgresql..");

    // adapter libs may use frameworks but never depend on agent modules
    @ArchTest
    static final ArchRule adaptersNeverDependOnAgents = noClasses()
            .that().resideInAPackage("dev.sdlc.adapter..")
            .should().dependOnClassesThat().resideInAPackage("dev.sdlc.agent*..");

    private static String[] merge(String[] a, String[] b) {
        var out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
