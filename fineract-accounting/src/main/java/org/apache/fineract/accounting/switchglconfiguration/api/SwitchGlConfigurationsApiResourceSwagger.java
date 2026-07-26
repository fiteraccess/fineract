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
package org.apache.fineract.accounting.switchglconfiguration.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.fineract.accounting.glaccount.data.GLAccountData;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;

final class SwitchGlConfigurationsApiResourceSwagger {

    private SwitchGlConfigurationsApiResourceSwagger() {}

    @Schema(description = "GetSwitchGlConfigurationsResponse")
    public static final class GetSwitchGlConfigurationsResponse {

        private GetSwitchGlConfigurationsResponse() {}

        @Schema(example = "1")
        public Long id;
        @Schema(example = "NIBSS")
        public String switchCode;
        public EnumOptionData direction;
        public GLAccountData principalGlAccountData;
        public GLAccountData switchFeeGlAccountData;
        public GLAccountData bankCommissionGlAccountData;
        @Schema(example = "true")
        public Boolean active;
    }

    @Schema(description = "PostSwitchGlConfigurationsRequest")
    public static final class PostSwitchGlConfigurationsRequest {

        private PostSwitchGlConfigurationsRequest() {}

        @Schema(example = "NIBSS")
        public String switchCode;
        @Schema(example = "1")
        public Integer direction;
        @Schema(example = "23")
        public Long principalGlAccountId;
        @Schema(example = "24")
        public Long switchFeeGlAccountId;
        @Schema(example = "25")
        public Long bankCommissionGlAccountId;
        @Schema(example = "true")
        public Boolean active;
    }

    @Schema(description = "PostSwitchGlConfigurationsResponse")
    public static final class PostSwitchGlConfigurationsResponse {

        private PostSwitchGlConfigurationsResponse() {}

        @Schema(example = "1")
        public Long resourceId;
    }

    @Schema(description = "PutSwitchGlConfigurationsRequest")
    public static final class PutSwitchGlConfigurationsRequest {

        private PutSwitchGlConfigurationsRequest() {}

        @Schema(example = "false")
        public Boolean active;
    }

    @Schema(description = "PutSwitchGlConfigurationsResponse")
    public static final class PutSwitchGlConfigurationsResponse {

        private PutSwitchGlConfigurationsResponse() {}

        @Schema(example = "1")
        public Long resourceId;
    }

    @Schema(description = "DeleteSwitchGlConfigurationsResponse")
    public static final class DeleteSwitchGlConfigurationsResponse {

        private DeleteSwitchGlConfigurationsResponse() {}

        @Schema(example = "1")
        public Long resourceId;
    }
}
