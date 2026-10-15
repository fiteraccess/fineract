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
package org.apache.fineract.portfolio.eod.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

/**
 * What a GL step of a run produced, one row per mirror leg or zero-balance check, kept as columns so the "EOD GL
 * Results" report is plain SQL on every database. A re-run step replaces its own rows.
 */
@Entity
@Table(name = "m_eod_gl_result")
@NoArgsConstructor
@Getter
@Setter
public class EodGlResult extends AbstractPersistableCustom<Long> {

    public static final String KIND_MIRROR_LEG = "MIRROR_LEG";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private EodRun run;

    @Column(name = "step_name", nullable = false, length = 50)
    private String stepName;

    @Column(name = "row_no", nullable = false)
    private int rowNo;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "logical_name", length = 100)
    private String logicalName;

    @Column(name = "fin_source_gl", length = 45)
    private String finSourceGl;

    @Column(name = "fin_bridge_gl", length = 45)
    private String finBridgeGl;

    @Column(name = "flex_source_gl", length = 45)
    private String flexSourceGl;

    @Column(name = "flex_bridge_gl", length = 45)
    private String flexBridgeGl;

    @Column(name = "flex_source_branch", length = 20)
    private String flexSourceBranch;

    @Column(name = "flex_bridge_branch", length = 20)
    private String flexBridgeBranch;

    @Column(name = "side", length = 10)
    private String side;

    /** The mirror source GL's normal side; with {@code flipped} it says which way the leg moved. */
    @Column(name = "normal_side", length = 10)
    private String normalSide;

    @Column(name = "flipped")
    private Boolean flipped;

    /** Signed: a flipped (net-negative) leg is stored negative. */
    @Column(name = "amount", precision = 19, scale = 6)
    private BigDecimal amount;

    @Column(name = "expected", precision = 19, scale = 6)
    private BigDecimal expected;

    @Column(name = "actual", precision = 19, scale = 6)
    private BigDecimal actual;

    @Column(name = "fin_status", length = 20)
    private String finStatus;

    @Column(name = "flex_status", length = 20)
    private String flexStatus;

    @Column(name = "drift_status", length = 20)
    private String driftStatus;

    @Column(name = "check_result", length = 20)
    private String checkResult;

    @Column(name = "reference", length = 140)
    private String reference;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static EodGlResult of(String kind, int rowNo, String logicalName) {
        EodGlResult result = new EodGlResult();
        result.kind = kind;
        result.rowNo = rowNo;
        result.logicalName = logicalName;
        return result;
    }
}
