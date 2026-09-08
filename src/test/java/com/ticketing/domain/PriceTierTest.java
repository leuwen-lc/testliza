package com.ticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PriceTierTest {

    @Test
    void containsExactlyTheFiveDocumentedTiers() {
        assertThat(PriceTier.values())
                .containsExactlyInAnyOrder(
                        PriceTier.PREMIUM,
                        PriceTier.STANDARD,
                        PriceTier.ECONOMY,
                        PriceTier.ACCESSIBLE,
                        PriceTier.RESTRICTED_VIEW);
    }
}
