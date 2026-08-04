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
package org.apache.fineract.accounting.glaccount.service;

import java.time.LocalDate;
import org.apache.fineract.accounting.glaccount.data.GLAccountBalanceData;
import org.apache.fineract.accounting.glaccount.data.GLAccountDetailsData;

/**
 * GL reads keyed on {@code gl_code} with balances computed from raw journal entries.
 *
 * <p>
 * Deliberately separate from {@link GLAccountReadPlatformService}, which serves the chart-of-accounts maintenance
 * screens and whose balance support reads the {@code organization_running_balance} column — a batch column refreshed
 * once daily by the "Update Accounting Running Balances" job, and exposed through a {@code Long} field despite the
 * column being {@code decimal(19,6)}. Nothing here touches that column.
 */
public interface GLAccountBalanceReadPlatformService {

    /**
     * @param glCode
     *            unique {@code acc_gl_account.gl_code}
     * @param asOnDate
     *            balance cut-off; defaults to the current business date when null
     * @param officeId
     *            restrict to one office by exact match; null means organisation-wide
     * @param currencyCode
     *            restrict to one currency; null resolves the account's sole currency, or throws if more than one
     * @throws org.apache.fineract.accounting.glaccount.exception.GLAccountNotFoundException
     *             when no account carries that code
     * @throws org.apache.fineract.accounting.glaccount.exception.GLAccountMultipleCurrenciesException
     *             when {@code currencyCode} is omitted and the account has been posted to in more than one currency
     */
    GLAccountDetailsData retrieveGLAccountDetailsByCode(String glCode, LocalDate asOnDate, Long officeId, String currencyCode);

    /**
     * @param fromDate
     *            inclusive window start
     * @param toDate
     *            inclusive window end
     * @throws org.apache.fineract.accounting.glaccount.exception.GLAccountNotFoundException
     *             when no account carries that code
     * @throws org.apache.fineract.accounting.glaccount.exception.GLAccountMultipleCurrenciesException
     *             when {@code currencyCode} is omitted and the account has been posted to in more than one currency
     *             within the window
     * @throws org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException
     *             when {@code fromDate}, {@code toDate} are missing, or {@code fromDate} is after {@code toDate}
     */
    GLAccountBalanceData retrieveGLAccountBalanceByCode(String glCode, LocalDate fromDate, LocalDate toDate, Long officeId,
            String currencyCode);
}
