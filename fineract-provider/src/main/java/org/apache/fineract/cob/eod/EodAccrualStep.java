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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.exception.MultiException;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodStep;
import org.apache.fineract.portfolio.eod.exception.EodStepFailedException;
import org.apache.fineract.portfolio.eod.service.EodRunService;
import org.apache.fineract.portfolio.savings.service.SavingsAccrualWritePlatformService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Step 1: accrue savings interest for the closing day, after the gate confirmed Fineract holds the day's transactions.
 * The totals credited to each accrued-interest-payable GL are recorded as the step's detail: the one figure Synapse
 * does not own, declared here for the INTEREST mirror.
 */
@Component
public class EodAccrualStep extends AbstractEodStep {

    public static final String NAME = "EOD_ACCRUAL";
    public static final String CODE_ACCRUAL = "EOD-ACCRUAL";

    static final String MAX_JOURNAL_ENTRY_ID_SQL = "SELECT COALESCE(MAX(id), 0) FROM acc_gl_journal_entry";
    /**
     * What this accrual run booked on the accrued-interest liability GLs: the net (credit minus debit) of every journal
     * entry for a savings accrual transaction (type 10) written after {@code ?}, so catch-up days are included and a
     * recalculation's reversals net against what they replace.
     */
    static final String ACCRUED_BY_GL_SQL = "SELECT gl.gl_code, gl.name,"
            + " SUM(CASE WHEN je.type_enum = 1 THEN je.amount ELSE -je.amount END) AS total FROM acc_gl_journal_entry je"
            + " JOIN m_savings_account_transaction t ON t.id = je.savings_transaction_id JOIN acc_gl_account gl ON gl.id = je.account_id"
            + " WHERE je.id > ? AND t.transaction_type_enum = 10 AND gl.classification_enum = 2"
            + " GROUP BY gl.gl_code, gl.name ORDER BY gl.gl_code";

    private final SavingsAccrualWritePlatformService accrualService;
    private final JdbcTemplate jdbcTemplate;

    public EodAccrualStep(EodRunService runService, FineractProperties properties, SavingsAccrualWritePlatformService accrualService,
            JdbcTemplate jdbcTemplate) {
        super(runService, properties);
        this.accrualService = accrualService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected Map<String, Object> run(EodRun run, EodStep step) {
        requirePrerequisite(run, EodReplayDrainGateStep.NAME);
        Long before = jdbcTemplate.queryForObject(MAX_JOURNAL_ENTRY_ID_SQL, Long.class);
        try {
            accrualService.addAccrualEntries(run.getBusinessDate());
        } catch (MultiException e) {
            throw new EodStepFailedException(CODE_ACCRUAL,
                    "accrual for " + run.getBusinessDate() + " failed for some accounts: " + e.getMessage(), e);
        }
        List<Map<String, Object>> byGl = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : jdbcTemplate.queryForList(ACCRUED_BY_GL_SQL, before == null ? 0L : before)) {
            BigDecimal amount = (BigDecimal) row.get("total");
            byGl.add(detail("glCode", row.get("gl_code"), "glName", row.get("name"), "amount", amount));
            total = total.add(amount);
        }
        return detail("tillDate", run.getBusinessDate().toString(), "accruedByGl", byGl, "accruedTotal", total, "note",
                "movement booked by this attempt; a retried step reports only its own");
    }

    @Override
    public String getEnumStyledName() {
        return NAME;
    }

    @Override
    public String getHumanReadableName() {
        return "EOD accrual: accrue savings interest for the closing day and record the totals per GL";
    }
}
