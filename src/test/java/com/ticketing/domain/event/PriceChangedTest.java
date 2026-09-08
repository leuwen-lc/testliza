package com.ticketing.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.domain.Currency;
import com.ticketing.domain.Money;
import com.ticketing.domain.PriceTier;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

class PriceChangedTest {

    @Test
    void retainsConstructedFields() {
        Money amount = Money.of(1500, Currency.USD);

        PriceChanged fact = new PriceChanged("event-1", PriceTier.STANDARD, amount, 3L);

        assertThat(fact.eventId()).isEqualTo("event-1");
        assertThat(fact.tier()).isEqualTo(PriceTier.STANDARD);
        assertThat(fact.amount()).isEqualTo(amount);
        assertThat(fact.version()).isEqualTo(3L);
    }

    @Test
    void isImmutableValueType() {
        assertThat(
                        Modifier.isFinal(PriceChanged.class.getModifiers())
                                || PriceChanged.class.isRecord())
                .as("PriceChanged must be a record or a final class")
                .isTrue();

        for (Method method : PriceChanged.class.getDeclaredMethods()) {
            assertThat(method.getName()).as("no setter methods allowed").doesNotStartWith("set");
        }

        for (var field : PriceChanged.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }
        }
    }
}
