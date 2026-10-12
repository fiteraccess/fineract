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

/** Something a step saw that did not stop the run but an operator must know: the report's exception rows. */
@Entity
@Table(name = "m_eod_exception")
@NoArgsConstructor
@Getter
@Setter
public class EodException extends AbstractPersistableCustom<Long> {

    public static final String SEVERITY_WARN = "WARN";
    public static final String SEVERITY_ERROR = "ERROR";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private EodRun run;

    @Column(name = "step_name", nullable = false, length = 50)
    private String stepName;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "message", nullable = false, length = 2000)
    private String message;

    @Column(name = "reference", length = 140)
    private String reference;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static EodException of(EodRun run, String stepName, String severity, String code, String message, String reference,
            LocalDateTime now) {
        EodException exception = new EodException();
        exception.run = run;
        exception.stepName = stepName;
        exception.severity = severity;
        exception.code = code;
        exception.message = message;
        exception.reference = reference;
        exception.createdAt = now;
        return exception;
    }
}
