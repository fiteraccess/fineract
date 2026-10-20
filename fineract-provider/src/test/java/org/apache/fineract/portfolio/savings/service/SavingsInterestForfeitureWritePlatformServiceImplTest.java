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
package org.apache.fineract.portfolio.savings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.savings.SavingsProductCategory;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSummary;
import org.apache.fineract.portfolio.savings.domain.SavingsInterestForfeitureRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsInterestForfeitureRepository.Transition;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SavingsInterestForfeitureWritePlatformServiceImplTest {

    private static final Long SAVINGS_ID = 41L;
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);
    private static final String BODY = """
            {"governingMonth":"2026-10-01","forfeited":true,"transitionSeq":5,
             "sourceTransitionId":"3f6c1a52-4c1e-4a0b-9a77-2f1f0b8f2b11","locale":"en","dateFormat":"yyyy-MM-dd"}""";

    private final SavingsAccountAssembler assembler = mock(SavingsAccountAssembler.class);
    private final SavingsInterestForfeitureRepository repository = mock(SavingsInterestForfeitureRepository.class);
    private final SavingsInterestForfeitureWritePlatformServiceImpl service = new SavingsInterestForfeitureWritePlatformServiceImpl(
            assembler, repository);
    private final SavingsAccount account = mock(SavingsAccount.class);
    private final SavingsProduct product = mock(SavingsProduct.class);
    private final SavingsAccountSummary summary = mock(SavingsAccountSummary.class);

    @BeforeEach
    void regularSavingsAccount() {
        when(assembler.assembleFromLightweight(SAVINGS_ID)).thenReturn(account);
        when(account.savingsProduct()).thenReturn(product);
        when(account.getSummary()).thenReturn(summary);
        when(product.getProductCategory()).thenReturn(SavingsProductCategory.DIGITAL);
        when(summary.getInterestPostedTillDate()).thenReturn(LocalDate.of(2026, 10, 1));
    }

    @Test
    void aNewerTransitionIsRecordedWithoutTouchingTheLedger() {
        when(repository.applyIfNewer(any())).thenReturn(true);

        CommandProcessingResult result = service.replayInterestForfeiture(SAVINGS_ID, command(BODY));

        verify(repository).applyIfNewer(new Transition(SAVINGS_ID, OCTOBER, true, 5L, "3f6c1a52-4c1e-4a0b-9a77-2f1f0b8f2b11"));
        assertThat(result.getResourceId()).isEqualTo(SAVINGS_ID);
        assertThat(result.getChanges()).containsEntry("applied", true).containsEntry("forfeited", true).containsEntry("governingMonth",
                "2026-10-01");
    }

    @Test
    void aStaleOrDuplicateSequenceSucceedsWithoutApplying() {
        when(repository.applyIfNewer(any())).thenReturn(false);

        CommandProcessingResult result = service.replayInterestForfeiture(SAVINGS_ID, command(BODY));

        assertThat(result.getChanges()).containsEntry("applied", false);
    }

    @Test
    void aMonthWhoseInterestIsPostedIsClosed() {
        when(summary.getInterestPostedTillDate()).thenReturn(LocalDate.of(2026, 10, 31));

        CommandProcessingResult result = service.replayInterestForfeiture(SAVINGS_ID, command(BODY));

        assertThat(result.getChanges()).containsEntry("applied", false);
        verify(repository, never()).applyIfNewer(any());
    }

    @Test
    void aProductThatIsNotRegularSavingsIsRejected() {
        when(product.getProductCategory()).thenReturn(SavingsProductCategory.GOAL);

        assertThatThrownBy(() -> service.replayInterestForfeiture(SAVINGS_ID, command(BODY)))
                .isInstanceOf(GeneralPlatformDomainRuleException.class);
        verify(repository, never()).applyIfNewer(any());
    }

    @Test
    void aGoverningMonthMustStartOnTheFirst() {
        assertThatThrownBy(() -> service.replayInterestForfeiture(SAVINGS_ID, command(BODY.replace("2026-10-01", "2026-10-15"))))
                .isInstanceOf(PlatformApiDataValidationException.class);
    }

    private static JsonCommand command(final String json) {
        return JsonCommand.from(json, JsonParser.parseString(json), new FromJsonHelper(), "SAVINGSACCOUNT", null, null, null, null, null,
                SAVINGS_ID, null, null, null, null, null, null, null);
    }
}
