package dev.sdlc.adapter.llm;

import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/** USD-per-million-token table, loaded from pricing.yaml (config, not code). */
public final class ModelPricing {
    private record Rate(double inputPerMTok, double outputPerMTok) {}
    private final Map<String, Rate> rates;

    private ModelPricing(Map<String, Rate> rates) { this.rates = rates; }

    @SuppressWarnings("unchecked")
    public static ModelPricing fromBundledYaml() {
        try (var in = ModelPricing.class.getResourceAsStream("/pricing.yaml")) {
            Map<String, Map<String, Number>> raw = new Yaml().load(in);
            var rates = new java.util.HashMap<String, Rate>();
            raw.forEach((model, io) -> rates.put(model,
                    new Rate(io.get("input").doubleValue(), io.get("output").doubleValue())));
            return new ModelPricing(Map.copyOf(rates));
        } catch (Exception e) { throw new IllegalStateException("pricing.yaml unreadable", e); }
    }

    /** Unknown model → 0.0 (caller logs). */
    public double costUsd(String model, long inputTokens, long outputTokens) {
        var rate = rates.get(model);
        if (rate == null) return 0.0;
        return inputTokens / 1_000_000.0 * rate.inputPerMTok()
                + outputTokens / 1_000_000.0 * rate.outputPerMTok();
    }

    public boolean knows(String model) { return rates.containsKey(model); }
}
