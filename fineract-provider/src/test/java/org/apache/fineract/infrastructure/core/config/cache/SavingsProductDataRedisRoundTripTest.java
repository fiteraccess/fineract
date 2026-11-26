/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.core.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.savings.data.SavingsProductData;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

/**
 * A savings product read served from Redis must carry the same fields as one read from the database. The class keeps
 * most of its state in private fields without getters, so a getter-based cache write used to drop them.
 */
class SavingsProductDataRedisRoundTripTest {

    private final ObjectMapper mapper = RedisCacheConfig.redisObjectMapper();
    private final GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

    @Test
    void cachedProductKeepsTheFieldsThatHaveNoGetter() {
        final SavingsProductData product = product();

        final byte[] cached = serializer.serialize(product);
        final String json = new String(cached, StandardCharsets.UTF_8);

        assertThat(json).contains("\"shortName\"", "\"description\"", "\"accountingRule\"", "\"interestCalculationType\"",
                "\"withdrawalFeeForTransfers\"", "\"productCategory\"");
        assertThat(json).contains("SVNG", "Everyday savings", "accountingRuleType.cash");
    }

    @Test
    void productReadBackFromTheCacheEqualsTheOneWritten() {
        final SavingsProductData product = product();

        final Object readBack = serializer.deserialize(serializer.serialize(product));

        assertThat(readBack).isInstanceOf(SavingsProductData.class);
        final String written = new String(serializer.serialize(product), StandardCharsets.UTF_8);
        final String reread = new String(serializer.serialize(readBack), StandardCharsets.UTF_8);
        assertThat(reread).isEqualTo(written);
    }

    private static SavingsProductData product() {
        final CurrencyData currency = new CurrencyData("NGN", 2, null);
        final EnumOptionData daily = new EnumOptionData(1L, "savings.interest.period.savingsCompoundingInterestPeriodType.daily", "Daily");
        final EnumOptionData monthly = new EnumOptionData(4L, "savings.interest.posting.period.savingsPostingInterestPeriodType.monthly",
                "Monthly");
        final EnumOptionData dailyBalance = new EnumOptionData(1L, "savingsInterestCalculationType.dailybalance", "Daily Balance");
        final EnumOptionData days365 = new EnumOptionData(365L, "savingsInterestCalculationDaysInYearType.days365", "365 Days");
        final EnumOptionData lockinMonths = new EnumOptionData(2L, "savings.lockin.savingsPeriodFrequencyType.months", "Months");
        final EnumOptionData cashAccounting = new EnumOptionData(2L, "accountingRuleType.cash", "CASH BASED");
        final SavingsProductData product = SavingsProductData.instance(7L, "Savings", "SVNG", "Everyday savings", currency,
                new BigDecimal("4.5"), daily, monthly, dailyBalance, days365, new BigDecimal("100.00"), 3, lockinMonths, true,
                cashAccounting, true, new BigDecimal("500.00"), new BigDecimal("50.00"), true, new BigDecimal("1000.00"), true,
                new BigDecimal("10.00"), new BigDecimal("12.0"), new BigDecimal("5.00"), false, null, true, 30L, 60L, 90L);
        product.setProductCategory("REGULAR");
        return product;
    }
}
