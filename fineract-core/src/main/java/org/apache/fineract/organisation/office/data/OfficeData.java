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
package org.apache.fineract.organisation.office.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import org.apache.fineract.infrastructure.core.domain.ExternalId;

/**
 * Immutable data object for office data.
 */
@Getter
public class OfficeData implements Serializable {

    private final Long id;
    private final String name;
    private final String nameDecorated;
    private final ExternalId externalId;
    private final LocalDate openingDate;
    private final String hierarchy;
    private final Long parentId;
    private final String parentName;
    private final Collection<OfficeData> allowedParents;

    // import fields
    private transient Integer rowIndex;
    private String locale;
    private String dateFormat;

    public static OfficeData importInstance(final String name, final Long parentId, final LocalDate openingDate,
            final ExternalId externalId) {
        return new OfficeData(null, name, null, externalId, openingDate, null, parentId, null, null);
    }

    public void setImportFields(final Integer rowIndex, final String locale, final String dateFormat) {
        this.rowIndex = rowIndex;
        this.locale = locale;
        this.dateFormat = dateFormat;
    }

    public static OfficeData testInstance(final Long id, final String name) {
        return new OfficeData(id, name, null, null, null, null, null, null, null);
    }

    public static OfficeData dropdown(final Long id, final String name, final String nameDecorated) {
        return new OfficeData(id, name, nameDecorated, null, null, null, null, null, null);
    }

    public static OfficeData template(final List<OfficeData> parentLookups, final LocalDate defaultOpeningDate) {
        return new OfficeData(null, null, null, null, defaultOpeningDate, null, null, null, parentLookups);
    }

    public static OfficeData appendedTemplate(final OfficeData office, final Collection<OfficeData> allowedParents) {
        return new OfficeData(office.id, office.name, office.nameDecorated, office.externalId, office.openingDate, office.hierarchy,
                office.parentId, office.parentName, allowedParents);
    }

    @JsonCreator
    public OfficeData(@JsonProperty("id") final Long id, @JsonProperty("name") final String name,
            @JsonProperty("nameDecorated") final String nameDecorated, @JsonProperty("externalId") final ExternalId externalId,
            @JsonProperty("openingDate") final LocalDate openingDate, @JsonProperty("hierarchy") final String hierarchy,
            @JsonProperty("parentId") final Long parentId, @JsonProperty("parentName") final String parentName,
            @JsonProperty("allowedParents") final Collection<OfficeData> allowedParents) {
        this.id = id;
        this.name = name;
        this.nameDecorated = nameDecorated;
        this.externalId = externalId;
        this.openingDate = openingDate;
        this.hierarchy = hierarchy;
        this.parentName = parentName;
        this.parentId = parentId;
        this.allowedParents = allowedParents;
    }

    public boolean hasIdentifyOf(final Long officeId) {
        return this.id.equals(officeId);
    }
}
