package dev.sdlc.adapter.llm;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelPricingTest {
    @Test
    void computesCostFromBundledTable() {
        var pricing = ModelPricing.fromBundledYaml();
        // claude-sonnet-4-6: 3.00 / 15.00 USD per 1M tokens (config, not code — pricing.yaml)
        double cost = pricing.costUsd("claude-sonnet-4-6", 1_000_000, 1_000_000);
        assertThat(cost).isEqualTo(18.0);
    }

    @Test
    void unknownModelCostsZero() {
        var pricing = ModelPricing.fromBundledYaml();
        assertThat(pricing.costUsd("mystery-model", 1000, 1000)).isZero();
    }
}
