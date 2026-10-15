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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One zero-balance check: a GL on one side whose balance must equal {@code expected}; {@code actual} is null when
 * unread.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SynapseZeroCheckResult {

    public static final String RESULT_CLEAR = "CLEAR";
    public static final String RESULT_FAIL = "FAIL";
    public static final String RESULT_UNVERIFIED = "UNVERIFIED";
    public static final String SIDE_FINERACT = "FINERACT";
    public static final String SIDE_FLEXCUBE = "FLEXCUBE";

    private int checkNo;
    private String kind;
    private String name;
    private String side;
    private String glCode;
    private String branch;
    private BigDecimal expected;
    private BigDecimal actual;
    private String result;
    private String note;
}
