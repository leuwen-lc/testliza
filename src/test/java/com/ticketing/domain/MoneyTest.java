package com.ticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void constructsWithZeroAmount() {
        Money money = Money.of(0, Currency.USD);

        assertThat(money.amountMinor()).isZero();
        assertThat(money.currency()).isEqualTo(Currency.USD);
    }

    @Test
    void constructsWithPositiveAmount() {
        Money money = Money.of(1500, Currency.EUR);

        assertThat(money.amountMinor()).isEqualTo(1500);
        assertThat(money.currency()).isEqualTo(Currency.EUR);
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> Money.of(-1, Currency.USD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void instancesWithEqualAmountAndCurrencyAreEqual() {
        Money a = Money.of(500, Currency.GBP);
        Money b = Money.of(500, Currency.GBP);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void instancesWithDifferentAmountOrCurrencyAreNotEqual() {
        Money a = Money.of(500, Currency.GBP);

        assertThat(a).isNotEqualTo(Money.of(501, Currency.GBP));
        assertThat(a).isNotEqualTo(Money.of(500, Currency.USD));
    }

    @Test
    void neverExposesOrAcceptsFloatingPointTypes() {
        for (Method method : Money.class.getDeclaredMethods()) {
            assertThat(method.getReturnType())
                    .as("return type of %s", method.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
            assertThat(method.getParameterTypes())
                    .as("parameter types of %s", method.getName())
                    .doesNotContain(double.class, float.class, Double.class, Float.class);
        }
    }
}
