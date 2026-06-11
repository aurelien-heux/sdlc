package dev.sdlc.agentdesign.application;

import dev.sdlc.agentdesign.domain.*;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.List;

/** Parses the model's final JSON into a DesignDraft; tolerates a ```json fence. */
public final class DesignDraftParser {

    public DesignDraft parse(String modelOutput) {
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
            var elements = root.getJsonArray("elements").stream().map(JsonValue::asJsonObject)
                    .map(o -> new DesignElementDraft(o.getString("title"), o.getString("description")))
                    .toList();
            var adrs = root.getJsonArray("adrs").stream().map(JsonValue::asJsonObject)
                    .map(o -> new AdrDraft(o.getString("title"), o.getString("context", ""),
                            o.getString("decision"),
                            o.getJsonArray("alternatives").stream().map(JsonValue::asJsonObject)
                                    .map(a -> new AdrDraft.Alternative(a.getString("option"),
                                            a.getString("tradeoff", "")))
                                    .toList(),
                            strings(o, "consequences")))
                    .toList();
            var contracts = root.getJsonArray("apiContracts").stream().map(JsonValue::asJsonObject)
                    .map(o -> new ApiContractDraft(o.getString("title"), o.getString("contract")))
                    .toList();
            return new DesignDraft(elements, adrs, contracts);
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
