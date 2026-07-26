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
package org.apache.fineract.accounting.switchglconfiguration.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.common.AccountingDropdownReadPlatformService;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.apache.fineract.accounting.switchglconfiguration.data.SwitchGlConfigurationData;
import org.apache.fineract.accounting.switchglconfiguration.domain.SwitchDirection;
import org.apache.fineract.accounting.switchglconfiguration.exception.SwitchGlConfigurationNotFoundException;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SwitchGlConfigurationReadPlatformServiceImpl implements SwitchGlConfigurationReadPlatformService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountingDropdownReadPlatformService accountingDropdownReadPlatformService;
    private final SwitchGlConfigurationMapper mapper = new SwitchGlConfigurationMapper();

    @Override
    public List<SwitchGlConfigurationData> retrieveAll() {
        final String sql = "select " + this.mapper.schema() + " order by sgc.switch_code, sgc.direction";
        return this.jdbcTemplate.query(sql, this.mapper); // NOSONAR
    }

    @Override
    public SwitchGlConfigurationData retrieveOne(final Long id) {
        try {
            final String sql = "select " + this.mapper.schema() + " where sgc.id = ?";
            return this.jdbcTemplate.queryForObject(sql, this.mapper, new Object[] { id }); // NOSONAR
        } catch (final EmptyResultDataAccessException e) {
            throw new SwitchGlConfigurationNotFoundException(id);
        }
    }

    @Override
    public SwitchGlConfigurationData addTemplateDetails(final SwitchGlConfigurationData switchGlConfigurationData) {
        final Map<String, List<GLAccountData>> accountOptions = this.accountingDropdownReadPlatformService.retrieveAccountMappingOptions();
        switchGlConfigurationData.setGlAccountOptions(accountOptions);
        switchGlConfigurationData.setDirectionOptions(directionOptions());
        return switchGlConfigurationData;
    }

    @Override
    public SwitchGlConfigurationData retrieveTemplate() {
        return addTemplateDetails(new SwitchGlConfigurationData());
    }

    private static List<EnumOptionData> directionOptions() {
        final List<EnumOptionData> options = new ArrayList<>();
        for (final SwitchDirection direction : SwitchDirection.values()) {
            options.add(toEnumOptionData(direction));
        }
        return options;
    }

    private static EnumOptionData toEnumOptionData(final SwitchDirection direction) {
        return new EnumOptionData(direction.getValue().longValue(), direction.getCode(), direction.name());
    }

    private static final class SwitchGlConfigurationMapper implements RowMapper<SwitchGlConfigurationData> {

        private final String sql;

        SwitchGlConfigurationMapper() {
            final StringBuilder sb = new StringBuilder(400);
            sb.append(" sgc.id as id, sgc.switch_code as switchCode, sgc.direction as direction, sgc.active as active, ");
            sb.append(" principal.id as principalId, principal.name as principalName, principal.gl_code as principalCode, ");
            sb.append(" fee.id as feeId, fee.name as feeName, fee.gl_code as feeCode, ");
            sb.append(" commission.id as commissionId, commission.name as commissionName, commission.gl_code as commissionCode ");
            sb.append(" from acc_gl_switch_configuration sgc ");
            sb.append(" join acc_gl_account principal on principal.id = sgc.principal_gl_account_id ");
            sb.append(" left join acc_gl_account fee on fee.id = sgc.switch_fee_gl_account_id ");
            sb.append(" left join acc_gl_account commission on commission.id = sgc.bank_commission_gl_account_id ");
            this.sql = sb.toString();
        }

        public String schema() {
            return this.sql;
        }

        @Override
        public SwitchGlConfigurationData mapRow(final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {
            final Long id = JdbcSupport.getLong(rs, "id");
            final String switchCode = rs.getString("switchCode");
            final Integer directionValue = JdbcSupport.getInteger(rs, "direction");
            final boolean active = rs.getBoolean("active");

            final GLAccountData principalGlAccountData = new GLAccountData().setId(JdbcSupport.getLong(rs, "principalId"))
                    .setName(rs.getString("principalName")).setGlCode(rs.getString("principalCode"));

            GLAccountData switchFeeGlAccountData = null;
            final Long feeId = JdbcSupport.getLong(rs, "feeId");
            if (feeId != null) {
                switchFeeGlAccountData = new GLAccountData().setId(feeId).setName(rs.getString("feeName"))
                        .setGlCode(rs.getString("feeCode"));
            }

            GLAccountData bankCommissionGlAccountData = null;
            final Long commissionId = JdbcSupport.getLong(rs, "commissionId");
            if (commissionId != null) {
                bankCommissionGlAccountData = new GLAccountData().setId(commissionId).setName(rs.getString("commissionName"))
                        .setGlCode(rs.getString("commissionCode"));
            }

            final EnumOptionData direction = toEnumOptionData(SwitchDirection.fromInt(directionValue));

            return new SwitchGlConfigurationData(id, switchCode, direction, principalGlAccountData, switchFeeGlAccountData,
                    bankCommissionGlAccountData, active);
        }
    }
}
