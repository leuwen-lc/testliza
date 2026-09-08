package com.ticketing.domain.event;

import com.ticketing.domain.Money;
import com.ticketing.domain.PriceTier;

import java.util.Objects;

public record PriceChanged(String eventId, PriceTier tier, Money amount, long version) {

    public PriceChanged {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(tier, "tier must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
