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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Synapse's view of one GL mirror run ({@code /api/v1/proxy/eod/mirror-runs}): the run and the legs it posts to
 * Fineract and queues for Flexcube.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SynapseMirrorRun {

    public static final String STATUS_PLANNING = "PLANNING";
    public static final String STATUS_POSTING = "POSTING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION";

    private Run run;
    private List<SynapseMirrorLeg> legs;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Run {

        private Long id;
        private LocalDate businessDate;
        private String job;
        private String status;
        private int attempts;
        private String lastError;
        private boolean acceptUnresolvedCommission;
    }
}
