package dev.sdlc.agentspec.application;

import dev.sdlc.agentspec.domain.*;
import dev.sdlc.domain.ArtifactId;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.StringReader;
import java.util.List;

/** Parses the model's final JSON answer; tolerates a ```json fence around it. */
public final class SpecDraftParser {

    public record Parsed(SpecificationDraft draft, List<String> assumptions, TestabilityReport report) {}

    public Parsed parse(String modelOutput, ArtifactId specId, List<ArtifactId> derivesFrom) {
        String json = modelOutput.strip();
        if (json.startsWith("```"))
            json = json.substring(json.indexOf('\n') + 1, json.lastIndexOf("```")).strip();
        JsonObject root;
        try (var reader = Json.createReader(new StringReader(json))) {
            root = reader.readObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("model output is not the expected JSON: " + e.getMessage(), e);
        }
        try {
            var criteria = root.getJsonArray("criteria").stream()
                    .map(v -> v.asJsonObject())
                    .map(o -> new AcceptanceCriterion(o.getString("scenario"), stringOrJoinedArray(o.get("steps"))))
                    .toList();
            var constraints = root.getJsonArray("constraints").stream()
                    .map(v -> ((jakarta.json.JsonString) v).getString()).toList();
            var assumptions = root.getJsonArray("assumptions").stream()
                    .map(v -> ((jakarta.json.JsonString) v).getString()).toList();
            var flags = root.getJsonArray("untestable").stream()
                    .map(v -> v.asJsonObject())
                    .map(o -> new TestabilityReport.Flag(ArtifactId.of(o.getString("id")), o.getString("reason")))
                    .toList();
            var draft = new SpecificationDraft(specId, root.getString("title"), derivesFrom, criteria, constraints);
            return new Parsed(draft, assumptions, new TestabilityReport(derivesFrom, flags));
        } catch (NullPointerException | ClassCastException e) {
            throw new IllegalArgumentException("model JSON missing or mistyped field: " + e.getMessage(), e);
        }
    }

    // some models emit Gherkin steps as an array of lines rather than one newline-joined string
    private static String stringOrJoinedArray(jakarta.json.JsonValue v) {
        if (v instanceof jakarta.json.JsonArray a)
            return a.stream()
                    .map(e -> ((jakarta.json.JsonString) e).getString())
                    .collect(java.util.stream.Collectors.joining("\n"));
        return ((jakarta.json.JsonString) v).getString();
    }
}
