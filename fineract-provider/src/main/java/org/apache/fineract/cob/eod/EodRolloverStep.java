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
package org.apache.fineract.cob.eod;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.data.service.BusinessDateDTO;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseBusinessDateResponse;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseEodCalendar;
import org.apache.fineract.portfolio.savings.service.synapse.SynapsePostingException;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Step 0, the two-phase rollover: Fineract's business date advances from D to the next business date N (the first date
 * after D that is not a head-office holiday) first, then Synapse is told, so Synapse can lag Fineract but never lead
 * it. The run's own context is then set to close D (COB date D, business date N) whatever Fineract's persisted COB date
 * did. N is checked (a period covers it; a new year is fully set up) and fixed on the run before Fineract moves.
 * Idempotent: a resumed run finds Fineract already on N and only repeats the push.
 */
@Component
public class EodRolloverStep extends AbstractEodStep {

    public static final String NAME = "EOD_ROLLOVER";
    public static final String CODE_PREFLIGHT = "EOD-PREFLIGHT";
    public static final String CODE_ROLLOVER = "EOD-ROLLOVER";
    public static final String CODE_SYNAPSE_PUSH = "EOD-SYNAPSE-PUSH";
    public static final String CODE_COB_DATE_NOT_ADJUSTED = "EOD-COB-DATE-NOT-ADJUSTED";
    public static final String CODE_PERIOD_MISSING = "EOD-PERIOD-MISSING";
    public static final String CODE_YEAR_NOT_READY = "EOD-YEAR-NOT-READY";

    private final ConfigurationDomainService configurationDomainService;
    private final BusinessDateReadPlatformService businessDateReadPlatformService;
    private final BusinessDateWritePlatformService businessDateWritePlatformService;
    private final ObjectProvider<SynapseTransactionClient> synapseClient;
    private final NextBusinessDate nextBusinessDate;

    public EodRolloverStep(EodRunService runService, FineractProperties properties, ConfigurationDomainService configurationDomainService,
            BusinessDateReadPlatformService businessDateReadPlatformService,
            BusinessDateWritePlatformService businessDateWritePlatformService, ObjectProvider<SynapseTransactionClient> synapseClient,
            NextBusinessDate nextBusinessDate) {
        super(runService, properties);
        this.configurationDomainService = configurationDomainService;
        this.businessDateReadPlatformService = businessDateReadPlatformService;
        this.businessDateWritePlatformService = businessDateWritePlatformService;
        this.synapseClient = synapseClient;
        this.nextBusinessDate = nextBusinessDate;
    }

    /**
     * Every later step, and the savings jobs they call, read the day to close from the thread: COB_DATE = D (what the
     * COB context reads) and BUSINESS_DATE = N. A resumed run skips the rollover, so the tasklet calls this too.
     */
    public static void useClosingDates(LocalDate closing, LocalDate next) {
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, next, BusinessDateType.COB_DATE, closing)));
    }

    @Override
    protected Map<String, Object> run(EodRun run, EodStep step) {
        LocalDate closing = run.getBusinessDate();
        if (!configurationDomainService.isBusinessDateEnabled()) {
            throw new EodStepFailedException(CODE_PREFLIGHT,
                    "enable-business-date is off in Fineract; the chain cannot roll the business date");
        }
        LocalDate current = businessDateReadPlatformService.getBusinessDates().get(BusinessDateType.BUSINESS_DATE);
        if (current == null) {
            throw new EodStepFailedException(CODE_PREFLIGHT, "Fineract has no BUSINESS_DATE row to advance");
        }
        SynapseTransactionClient client = synapseClient.getIfAvailable();
        if (client == null) {
            throw new EodStepFailedException(CODE_SYNAPSE_DISABLED,
                    "fineract.synapse.enabled is off: Synapse cannot be told the business date");
        }
        LocalDate next = run.getNextBusinessDate() != null ? run.getNextBusinessDate() : planNext(client, closing);
        boolean alreadyRolled = next.equals(current);
        if (!alreadyRolled && !closing.equals(current)) {
            throw new EodStepFailedException(CODE_PREFLIGHT,
                    "Fineract's business date is " + current + " but this run closes " + closing + "; refusing to roll");
        }
        if (run.getNextBusinessDate() == null) {
            runService.saveNextBusinessDate(run, next);
        }
        if (!alreadyRolled) {
            try {
                businessDateWritePlatformService.updateBusinessDate(BusinessDateDTO.builder().type(BusinessDateType.BUSINESS_DATE)
                        .description(BusinessDateType.BUSINESS_DATE.getDescription()).date(next).build());
            } catch (AbstractPlatformException e) {
                throw new EodStepFailedException(CODE_ROLLOVER, "Fineract refused to advance the business date: " + e.getMessage(), e);
            }
        }
        if (!configurationDomainService.isCOBDateAdjustmentEnabled()) {
            runService.recordException(run, NAME, EodException.SEVERITY_WARN, CODE_COB_DATE_NOT_ADJUSTED,
                    "enable-automatic-cob-date-adjustment is off: Fineract's own COB_DATE did not follow the rollover; the chain still closes "
                            + closing,
                    null);
        }
        useClosingDates(closing, next);

        SynapseBusinessDateResponse pushed;
        try {
            pushed = client.postBusinessDate(next, closing);
        } catch (SynapsePostingException e) {
            throw new EodStepFailedException(CODE_SYNAPSE_PUSH, e.getMessage(), e);
        }
        return detail("from", closing.toString(), "to", next.toString(), "holidaysSkipped", ChronoUnit.DAYS.between(closing, next) - 1,
                "alreadyRolled", alreadyRolled, "synapseBusinessDate", pushed == null ? null : String.valueOf(pushed.getBusinessDate()));
    }

    /**
     * Resolves N and refuses it unless Synapse's financial calendar is ready: entering a new year needs that year's
     * twelve periods and confirmed holidays, and N needs a period of its own. Nothing has moved yet when this halts.
     */
    private LocalDate planNext(SynapseTransactionClient client, LocalDate closing) {
        LocalDate next = nextBusinessDate.after(closing);
        SynapseEodCalendar calendar = readCalendar(client, closing, next);
        List<String> notReady = calendar.getYearReadinessProblems();
        if (!notReady.isEmpty()) {
            throw new EodStepFailedException(CODE_YEAR_NOT_READY,
                    "cannot roll from " + closing + " into " + next.getYear() + ": " + String.join("; ", notReady));
        }
        if (calendar.getNextPeriod() == null) {
            throw new EodStepFailedException(CODE_PERIOD_MISSING, "no financial period covers the next business date " + next
                    + " (expected " + String.format("M%02d", next.getMonthValue()) + "); the business date stays " + closing);
        }
        return next;
    }

    @Override
    public String getEnumStyledName() {
        return NAME;
    }

    @Override
    public String getHumanReadableName() {
        return "EOD rollover: advance Fineract's business date past holidays and push it to Synapse";
    }
}
