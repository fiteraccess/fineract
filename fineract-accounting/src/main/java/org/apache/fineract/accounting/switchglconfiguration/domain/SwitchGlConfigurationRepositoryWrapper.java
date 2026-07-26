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
package org.apache.fineract.accounting.switchglconfiguration.domain;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.switchglconfiguration.exception.SwitchGlConfigurationNotActiveException;
import org.apache.fineract.accounting.switchglconfiguration.exception.SwitchGlConfigurationNotFoundException;
import org.springframework.stereotype.Service;

/**
 * <p>
 * Wrapper for {@link SwitchGlConfigurationRepository} that adds NULL checking and error handling. Mirrors
 * {@code FinancialActivityAccountRepositoryWrapper} - AB-338's posting engine calls
 * {@link #findBySwitchCodeAndDirectionWithNotFoundDetection(String, SwitchDirection)} to resolve the GL account(s) for
 * a savings transaction's {@code switchCode}, exactly the same shape as the existing EMT Levy lookup.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SwitchGlConfigurationRepositoryWrapper {

    private final SwitchGlConfigurationRepository repository;

    public SwitchGlConfiguration findOneWithNotFoundDetection(final Long id) {
        return this.repository.findById(id).orElseThrow(() -> new SwitchGlConfigurationNotFoundException(id));
    }

    /**
     * Looks up a (switchCode, direction) pair, rejecting (404) if it isn't configured at all, and rejecting (409) if
     * it's configured but disabled. Callers must invoke this before any money-moving posting occurs.
     */
    public SwitchGlConfiguration findBySwitchCodeAndDirectionWithNotFoundDetection(final String switchCode,
            final SwitchDirection direction) {
        final SwitchGlConfiguration configuration = this.repository.findBySwitchCodeAndDirection(switchCode, direction.getValue());
        if (configuration == null) {
            throw new SwitchGlConfigurationNotFoundException(switchCode, direction);
        }
        if (!configuration.isActive()) {
            throw new SwitchGlConfigurationNotActiveException(switchCode, direction);
        }
        return configuration;
    }

    public void save(final SwitchGlConfiguration configuration) {
        this.repository.save(configuration);
    }

    public void saveAndFlush(final SwitchGlConfiguration configuration) {
        this.repository.saveAndFlush(configuration);
    }

    public void delete(final SwitchGlConfiguration configuration) {
        this.repository.delete(configuration);
    }
}
