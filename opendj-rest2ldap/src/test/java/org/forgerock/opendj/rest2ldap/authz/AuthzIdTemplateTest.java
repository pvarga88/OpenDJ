/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2013-2016 ForgeRock AS.
 */
package org.forgerock.opendj.rest2ldap.authz;

import static org.fest.assertions.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.forgerock.json.JsonValue;
import org.forgerock.testng.ForgeRockTestCase;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the AuthzIdTemplate class.
 */
@SuppressWarnings({ "javadoc" })
@Test
public final class AuthzIdTemplateTest extends ForgeRockTestCase {

    @DataProvider
    public Object[][] templateData() {
        // @formatter:off
        // [template as set in json configuration file]
        // [excepted result after placeholders resolution]
        // [Values to use to resolve the placeholders]
        return new Object[][] {
            {
                "dn:uid={uid},ou={realm},dc=example,dc=com",
                "uid=test.user,ou=acme,dc=example,dc=com",
                map("uid", "test.user", "realm", "acme")
            },
            {
                // Should perform DN quoting.
                "dn:uid={uid},ou={realm},dc=example,dc=com",
                "uid=test.user,ou=test\\+cn\\=quoting,dc=example,dc=com",
                map("uid", "test.user", "realm", "test+cn=quoting")
            },
            {
                "u:{uid}@{realm}.example.com",
                "test.user@acme.example.com",
                map("uid", "test.user", "realm", "acme")
            },
            {
                // Should not perform any DN quoting.
                "u:{uid}@{realm}.example.com",
                "test.user@test+cn=quoting.example.com",
                map("uid", "test.user", "realm", "test+cn=quoting")
            },
            {
                // Should resolve boolean and numbers
                "u:{uid}.{numericid}.{testboolean}@{realm}.example.com",
                "test.42.true@test.example.com",
                map("uid", "test", "numericid", 42, "testboolean", true, "realm", "test")
            }
        };
        // @formatter:on

    }

    @Test(dataProvider = "templateData")
    public void testTemplates(final String template, final String expected, Map<String, Object> principals)
            throws Exception {
        assertThat(new AuthzIdTemplate(template).formatAsAuthzId(new JsonValue(principals)))
                .isEqualTo(expected);
    }

    @DataProvider
    public Object[][] invalidTemplateData() {
        // @formatter:off
        return new Object[][] {
            {
                "dn:uid={uid},ou={realm},dc=example,dc=com",
                map("uid", "test.user")
            },
            {
                // Malformed DN.
                "dn:{dn}",
                map("dn", "uid")
            },
            {
                "u:{uid}@{realm}.example.com",
                map("uid", "test.user")
            },
        };
        // @formatter:on

    }

    @Test(dataProvider = "invalidTemplateData", expectedExceptions = IllegalArgumentException.class)
    public void testInvalidTemplateData(final String template, Map<String, Object> principals)
            throws Exception {
        new AuthzIdTemplate(template).formatAsAuthzId(new JsonValue(principals));
    }

    @DataProvider
    public Object[][] invalidTemplates() {
        // @formatter:off
        return new Object[][] {
            {
                "x:uid={uid},ou={realm},dc=example,dc=com"
            },
        };
        // @formatter:on
    }

    @Test(dataProvider = "invalidTemplates", expectedExceptions = IllegalArgumentException.class)
    public void testInvalidTemplates(final String template) throws Exception {
        new AuthzIdTemplate(template);
    }

    private Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }
}
