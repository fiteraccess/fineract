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
package org.apache.fineract.integrationtests.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class TenantJdbcSupport {

    private TenantJdbcSupport() {}

    public static JdbcTemplate tenantJdbc() {
        String host = resolve("FINERACT_DEFAULT_TENANTDB_HOSTNAME", "localhost");
        String port = resolve("FINERACT_DEFAULT_TENANTDB_PORT", "5432");
        String dbName = resolve("FINERACT_DEFAULT_TENANTDB_NAME", "fineract_default");
        String user = resolve("FINERACT_DEFAULT_TENANTDB_UID", "postgres");
        String password = resolve("FINERACT_DEFAULT_TENANTDB_PWD", "postgres");
        String url = resolve("FINERACT_DEFAULT_TENANTDB_URL", "jdbc:postgresql://" + host + ":" + port + "/" + dbName);

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        return new JdbcTemplate(ds);
    }

    private static String resolve(String envVar, String dflt) {
        String val = System.getenv(envVar);
        return val != null ? val : dflt;
    }
}
