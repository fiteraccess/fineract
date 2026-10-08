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

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EodRunRepository extends JpaRepository<EodRun, Long> {

    Optional<EodRun> findByBusinessDate(LocalDate businessDate);

    /** Earlier days still open: the chain closes one business date at a time, in order. */

    /** The earliest run not yet completed: what "Run now" resumes before any new day is opened. */
    Optional<EodRun> findFirstByStatusNotOrderByBusinessDateAsc(String status);
}
