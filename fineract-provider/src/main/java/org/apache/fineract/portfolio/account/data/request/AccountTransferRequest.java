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
package org.apache.fineract.portfolio.account.data.request;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AccountTransferRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String transferDescription;
    private String toOfficeId;
    private String toAccountType;
    private String dateFormat;
    private String transferAmount;
    private String toAccountId;
    private String fromClientId;
    private String locale;
    private String transferDate;
    private String fromAccountType;
    private String toClientId;
    private String fromAccountId;
    private String fromOfficeId;

    // AB-266: reference transactions pinned per leg — Fineract routes source refs under the withdrawal parent and
    // destination refs under the deposit parent. Each entry carries { "type": "EMT_LEVY", "amount": 50 }. Untyped
    // (List<Map>) so the API accepts and forwards the arrays unchanged; AccountTransfersWritePlatformServiceImpl
    // parses them via ReferenceTransaction.parseArray downstream.
    private List<Map<String, Object>> sourceReferenceTransactions;
    private List<Map<String, Object>> destinationReferenceTransactions;
}
