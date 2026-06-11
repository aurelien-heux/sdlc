package dev.sdlc.agentintent.application;

import dev.sdlc.agentintent.domain.*;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.List;

/** Parses the model's final JSON into an IntentDraft; tolerates a ```json fence. */
public final class IntentDraftParser {

    public IntentDraft parse(String modelOutput) {
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
            var goals = root.getJsonArray("goals").stream().map(JsonValue::asJsonObject)
                    .map(o -> new GoalDraft(o.getString("title"), o.getString("description", ""),
                            strings(o, "sourceQuotes"), strings(o, "assumptions")))
                    .toList();
            var requirements = root.getJsonArray("requirements").stream().map(JsonValue::asJsonObject)
                    .map(o -> new RequirementDraft(o.getString("title"), o.getString("description", ""),
                            o.getString("kind"), o.getString("moscow"),
                            o.getString("goalTitle", null),
                            strings(o, "sourceQuotes"), strings(o, "assumptions")))
                    .toList();
            var useCases = root.getJsonArray("useCases").stream().map(JsonValue::asJsonObject)
                    .map(o -> new UseCaseDraft(o.getString("title"), o.getString("actor"),
                            strings(o, "mainFlow"), strings(o, "altFlows"),
                            o.getString("requirementTitle", null),
                            strings(o, "sourceQuotes"), strings(o, "assumptions")))
                    .toList();
            return new IntentDraft(goals, requirements, useCases);
        } catch (NullPointerException | ClassCastException e) {
            throw new IllegalArgumentException("model JSON missing or mistyped field: " + e.getMessage(), e);
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        JsonArray arr = o.getJsonArray(key);
        if (arr == null) return List.of();
        return arr.stream().map(v -> ((JsonString) v).getString()).toList();
    }
}
