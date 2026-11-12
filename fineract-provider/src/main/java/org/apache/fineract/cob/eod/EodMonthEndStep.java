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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.closure.service.GLClosureWritePlatformService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.portfolio.eod.domain.EodException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.data.synapse.SynapseEodCalendar;
import org.apache.fineract.portfolio.savings.service.synapse.SynapseTransactionClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The month-end step: once the roll has left the closing day's period, closes every office's books through the period's
 * last day, so nothing can post into it again. The plan places it after every step that posts into the period, and a
 * closure binds only its own office, hence every office. Every other night it records that the period did not change.
 * The EOD run on a month's last business day is the month-end run; there is no separate job. Periods live in Synapse.
 */
@Component
public class EodMonthEndStep extends AbstractEodStep {

    public static final String NAME = "EOD_MONTH_END";
    public static final String CODE_PERIOD_UNKNOWN = "EOD-PERIOD-UNKNOWN";

    private final ObjectProvider<SynapseTransactionClient> synapseClient;
    private final GLClosureWritePlatformService closures;
    private final OfficeRepository officeRepository;

    public EodMonthEndStep(EodRunService runService, FineractProperties properties, ObjectProvider<SynapseTransactionClient> synapseClient,
            GLClosureWritePlatformService closures, OfficeRepository officeRepository) {
        super(runService, properties);
        this.synapseClient = synapseClient;
        this.closures = closures;
        this.officeRepository = officeRepository;
    }

    @Override
    protected Map<String, Object> run(EodRun run, EodStep step) {
        LocalDate closing = run.getBusinessDate();
        SynapseEodCalendar.Period period = readCalendar(requireSynapse(synapseClient), closing, run.rolledTo()).getClosingPeriod();
        if (period == null) {
            runService.recordException(run, NAME, EodException.SEVERITY_WARN, CODE_PERIOD_UNKNOWN,
                    "no financial period covers " + closing + "; its books cannot be closed by EOD", null);
            return detail("periodChanged", false, "reason", "no financial period covers " + closing);
        }
        String cycle = period.getFinancialCycle();
        if (!run.rolledTo().isAfter(period.getEndDate())) {
            return detail("periodChanged", false, "period", period.getPeriodCode(), "financialCycle", cycle);
        }
        List<Office> offices = officeRepository.findAll();
        List<Office> closed = new ArrayList<>();
        // A period that ends on a skipped holiday ends after the closing day: a closure may not be dated in the future.
        asOfNextBusinessDate(() -> {
            for (Office office : offices) {
                if (closures.closeOfficeThrough(office, period.getEndDate(), "EOD close of " + period.getPeriodCode() + " " + cycle)) {
                    closed.add(office);
                }
            }
        });
        return detail("periodChanged", true, "period", period.getPeriodCode(), "financialCycle", cycle, "closedThrough",
                period.getEndDate().toString(), "officesClosed", closed.size(), "officesAlreadyClosed", offices.size() - closed.size());
    }

    @Override
    public String getEnumStyledName() {
        return NAME;
    }

    @Override
    public String getHumanReadableName() {
        return "EOD month end: close every office's books through a period the business date has left";
    }
}
