package dev.sdlc.agentbacklog.application;

import dev.sdlc.agentbacklog.domain.BacklogDraft;
import dev.sdlc.agentbacklog.domain.BacklogItemDraft;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.List;

/** Parses the model's final JSON into a BacklogDraft; tolerates a ```json fence. */
public final class BacklogDraftParser {

    public BacklogDraft parse(String modelOutput) {
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
            var items = root.getJsonArray("items").stream().map(JsonValue::asJsonObject)
                    .map(o -> new BacklogItemDraft(o.getString("level"), o.getString("title"),
                            o.getString("description", ""),
                            o.containsKey("acceptanceHook") && o.isNull("acceptanceHook")
                                    ? null : o.getString("acceptanceHook", null),
                            o.getString("estimate"), strings(o, "dependsOn")))
                    .toList();
            return new BacklogDraft(items);
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
