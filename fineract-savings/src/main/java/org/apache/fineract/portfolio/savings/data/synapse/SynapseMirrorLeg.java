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
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One leg of a Synapse GL mirror run: an amount moved from a Fineract source GL to its bridge, and mirrored to
 * Flexcube.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SynapseMirrorLeg {

    public static final String FIN_POSTED = "POSTED";
    public static final String FIN_FAILED = "FAILED";
    public static final String FIN_SKIPPED = "SKIPPED";
    public static final String FLEX_SENT = "SENT";
    public static final String FLEX_DEAD = "DEAD";
    public static final String DRIFT = "DRIFT";

    private Long runId;
    private int rowNo;
    private Long mappingId;
    private String logicalName;
    private Long finSourceGlId;
    private String finSourceGlCode;
    private Long finBridgeGlId;
    private String normalSide;
    private FlexcubeGl flexSource;
    private FlexcubeGl flexBridge;
    private BigDecimal amount;
    private BigDecimal lateAmount;
    private LocalDate windowFrom;
    private LocalDate windowTo;
    private boolean flipped;
    private String reference;
    private String finStatus;
    private String finTxId;
    private String flexStatus;
    private String driftStatus;
    private String note;
    private String lastError;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FlexcubeGl {

        private String gl;
        private String branch;
    }
}
