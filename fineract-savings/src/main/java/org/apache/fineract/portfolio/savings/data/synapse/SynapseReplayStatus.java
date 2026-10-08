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
package org.apache.fineract.portfolio.savings.data.synapse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Synapse's answer to {@code GET /api/v1/proxy/eod/replay-status?businessDate=D}: what dated on or before D has not
 * reached Fineract yet. {@code drained} means nothing is in flight; {@code failedNewCount} blocks the run (the lists
 * beside the counts are bounded samples, the counts are exact).
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SynapseReplayStatus {

    private LocalDate businessDate;
    private LocalDate cutoff;
    private String cutoffSource;
    private InFlight inFlight;
    private long failedNewCount;
    private long failedLegacyCount;
    private List<Failure> failedNew;
    private List<Failure> failedLegacy;
    private long flexcubeOutboxPending;
    private Map<String, Map<String, Long>> interestRows;
    private boolean drained;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InFlight {

        private long pendingTx;
        private long postedToTbOnly;
        private long inboxPending;
        private long inboxRetrying;
        private long total;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Failure {

        private String reference;
        private String source;
        private String type;
        private String status;
        private LocalDate transactionDate;
        private String error;
    }
}
