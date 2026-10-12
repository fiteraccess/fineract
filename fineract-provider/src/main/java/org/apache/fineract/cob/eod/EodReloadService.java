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

import lombok.RequiredArgsConstructor;
import org.apache.fineract.cob.service.ReloadService;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.portfolio.eod.domain.EodRun;
import org.apache.fineract.portfolio.eod.domain.EodRunRepository;
import org.springframework.stereotype.Service;

/** The step runner reloads its item before every step; for a run that means the row as the last checkpoint left it. */
@Service
@RequiredArgsConstructor
public class EodReloadService implements ReloadService<EodRun> {

    private final EodRunRepository runRepository;

    @Override
    public <X extends AbstractPersistableCustom<Long>> boolean canReload(X input) {
        return input instanceof EodRun;
    }

    @Override
    public EodRun reload(EodRun input) {
        return runRepository.findById(input.getId()).orElse(input);
    }
}
