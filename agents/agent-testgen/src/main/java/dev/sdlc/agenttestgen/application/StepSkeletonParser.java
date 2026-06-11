package dev.sdlc.agenttestgen.application;

import dev.sdlc.agenttestgen.domain.StepSkeletonDraft;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.StringReader;

/** Parses the model's final JSON into a StepSkeletonDraft; tolerates a ```json fence. */
public final class StepSkeletonParser {

    public StepSkeletonDraft parse(String modelOutput) {
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
            return new StepSkeletonDraft(root.getString("language"), root.getString("content"));
        } catch (NullPointerException | ClassCastException e) {
            throw new IllegalArgumentException("model JSON missing or mistyped field: " + e.getMessage(), e);
        }
    }
}
