package dev.sdlc.adapter.graph;

import dev.sdlc.domain.ArtifactId;
import dev.sdlc.trace.TraceabilityGraphContract;
import dev.sdlc.trace.TraceabilityGraphPort;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresTraceabilityGraphTest extends TraceabilityGraphContract {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Override
    protected TraceabilityGraphPort newGraph() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        var graph = new PostgresTraceabilityGraph(ds);
        graph.initSchema();
        graph.truncate(); // fresh state per test (container is shared across the class)
        return graph;
    }

    @Test
    void impactOfStaysInteractiveAtOneThousandNodes() {
        // NFR-PERF smoke: chain GOAL <- REQ-1..1000, each deriving from the previous
        var g = graph;
        for (int i = 1; i <= 1000; i++) {
            var id = String.format("PERF-%04d", i);
            g.upsert(node(id, dev.sdlc.domain.NodeType.REQUIREMENT,
                    dev.sdlc.domain.NodeStatus.DRAFT, "s" + i));
            if (i > 1)
                g.link(dev.sdlc.trace.Edge.current(dev.sdlc.domain.EdgeType.DERIVES_FROM,
                        ArtifactId.of(id), ArtifactId.of(String.format("PERF-%04d", i - 1)),
                        "s" + (i - 1), "test", T0));
        }
        long start = System.nanoTime();
        var impacted = g.impactOf(ArtifactId.of("PERF-0001"));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(impacted).hasSize(999);
        assertThat(ms).isLessThan(200);
    }
}
