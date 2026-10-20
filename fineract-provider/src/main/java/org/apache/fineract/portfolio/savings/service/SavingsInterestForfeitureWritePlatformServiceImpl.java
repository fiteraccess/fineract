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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.portfolio.savings.SavingsProductCategory;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsInterestForfeitureRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsInterestForfeitureRepository.Transition;
import org.springframework.stereotype.Service;

/**
 * AB-566: replays Synapse's monthly withdrawal-limit decision for a Regular Savings account. Only the state is stored;
 * the end-of-day accrual reverses or re-books the month's accruals and month-end posting skips a forfeited month.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavingsInterestForfeitureWritePlatformServiceImpl implements SavingsInterestForfeitureWritePlatformService {

    public static final String GOVERNING_MONTH = "governingMonth";
    public static final String FORFEITED = "forfeited";
    public static final String TRANSITION_SEQ = "transitionSeq";
    public static final String SOURCE_TRANSITION_ID = "sourceTransitionId";
    private static final String RESOURCE = "savingsaccount.interestforfeiture";

    private final SavingsAccountAssembler savingsAccountAssembler;
    private final SavingsInterestForfeitureRepository forfeitureRepository;

    @Override
    public CommandProcessingResult replayInterestForfeiture(final Long savingsId, final JsonCommand command) {
        final Transition transition = parse(savingsId, command);
        final SavingsAccount account = this.savingsAccountAssembler.assembleFromLightweight(savingsId);
        if (account.savingsProduct().getProductCategory() != SavingsProductCategory.DIGITAL) {
            throw new GeneralPlatformDomainRuleException("error.msg.savingsaccount.interest.forfeiture.not.regular.savings",
                    "Interest forfeiture applies only to Regular Savings (DIGITAL) products; account " + savingsId + " is not one",
                    savingsId);
        }

        final boolean applied = !isMonthClosed(account, transition.governingMonth()) && this.forfeitureRepository.applyIfNewer(transition);
        if (!applied) {
            log.debug("Interest forfeiture replay ignored: account={} month={} seq={}", savingsId, transition.governingMonth(),
                    transition.transitionSeq());
        }

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("applied", applied);
        changes.put(FORFEITED, transition.forfeited());
        changes.put(GOVERNING_MONTH, DateTimeFormatter.ISO_LOCAL_DATE.format(transition.governingMonth()));
        return new CommandProcessingResultBuilder().withEntityId(savingsId).withSavingsId(savingsId).withOfficeId(account.officeId())
                .withClientId(account.clientId()).with(changes).build();
    }

    /** Interest for the month is posted (or settled as forfeited) once the posted-till date reaches its last day. */
    static boolean isMonthClosed(final SavingsAccount account, final LocalDate governingMonth) {
        final LocalDate postedTill = account.getSummary().getInterestPostedTillDate();
        return postedTill != null && !postedTill.isBefore(governingMonth.with(TemporalAdjusters.lastDayOfMonth()));
    }

    private static Transition parse(final Long savingsId, final JsonCommand command) {
        final List<ApiParameterError> errors = new ArrayList<>();
        final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource(RESOURCE);

        final LocalDate governingMonth = command.localDateValueOfParameterNamed(GOVERNING_MONTH);
        validator.reset().parameter(GOVERNING_MONTH).value(governingMonth).notNull();
        if (governingMonth != null && governingMonth.getDayOfMonth() != 1) {
            validator.reset().parameter(GOVERNING_MONTH).value(governingMonth).failWithCode("not.first.day.of.month",
                    "governingMonth must be the first day of a month");
        }
        final Boolean forfeited = command.booleanObjectValueOfParameterNamed(FORFEITED);
        validator.reset().parameter(FORFEITED).value(forfeited).notNull();
        final Long transitionSeq = command.longValueOfParameterNamed(TRANSITION_SEQ);
        validator.reset().parameter(TRANSITION_SEQ).value(transitionSeq).notNull().longZeroOrGreater();
        final String sourceTransitionId = command.stringValueOfParameterNamed(SOURCE_TRANSITION_ID);
        validator.reset().parameter(SOURCE_TRANSITION_ID).value(sourceTransitionId).ignoreIfNull().notExceedingLengthOf(100);

        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
        return new Transition(savingsId, governingMonth, forfeited, transitionSeq, sourceTransitionId);
    }
}
