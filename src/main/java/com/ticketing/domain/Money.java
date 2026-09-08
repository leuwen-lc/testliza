package com.ticketing.domain;

import java.util.Objects;

public final class Money {

    private final long amountMinor;
    private final Currency currency;

    private Money(long amountMinor, Currency currency) {
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public static Money of(long amountMinor, Currency currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor must not be negative: " + amountMinor);
        }
        return new Money(amountMinor, currency);
    }

    public long amountMinor() {
        return amountMinor;
    }

    public Currency currency() {
        return currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return amountMinor == other.amountMinor && currency == other.currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountMinor, currency);
    }

    @Override
    public String toString() {
        return amountMinor + " " + currency;
    }
}
