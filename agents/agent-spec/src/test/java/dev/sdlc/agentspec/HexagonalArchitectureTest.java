package dev.sdlc.agentspec;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "dev.sdlc")
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnNothingOutside = noClasses()
            .that().resideInAPackage("..agentspec.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..agentspec.application..", "..agentspec.adapters..",
                    "..agentspec.bootstrap..", "org.springframework..");

    @ArchTest
    static final ArchRule applicationNeverTouchesAdapters = noClasses()
            .that().resideInAPackage("..agentspec.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..agentspec.adapters..", "..agentspec.bootstrap..", "org.springframework..");

    @ArchTest
    static final ArchRule coreLibsAreFrameworkFree = noClasses()
            .that().resideInAnyPackage("dev.sdlc.domain..", "dev.sdlc.trace..", "dev.sdlc.agent..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "dev.langchain4j..", "org.springframework.ai..");
}
