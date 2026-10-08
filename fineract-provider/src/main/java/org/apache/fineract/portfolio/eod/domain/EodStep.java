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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

/** One step of a run: its checkpoint. A COMPLETED step is skipped when the run is resumed. */
@Entity
@Table(name = "m_eod_step")
@NoArgsConstructor
@Getter
@Setter
public class EodStep extends AbstractPersistableCustom<Long> {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private EodRun run;

    @Column(name = "step_name", nullable = false, length = 50)
    private String stepName;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /** JSON, what the step observed or produced (counts, totals, the last poll); text so both databases take it. */
    @Column(name = "detail")
    private String detail;

    public static EodStep start(EodRun run, String stepName, LocalDateTime now) {
        EodStep step = new EodStep();
        step.run = run;
        step.stepName = stepName;
        step.status = STATUS_RUNNING;
        step.startedAt = now;
        step.attempts = 1;
        return step;
    }

    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }
}
