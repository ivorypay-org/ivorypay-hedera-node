/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.mirror.importer.reader.balance.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.mirror.common.domain.balance.AccountBalance;
import com.hedera.mirror.importer.ImporterProperties;
import com.hedera.mirror.importer.exception.InvalidDatasetException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountBalanceLineParserV1Test {

    private static final long TIMESTAMP = 1596340377922333444L;
    private AccountBalanceLineParserV1 parser;
    private ImporterProperties importerProperties;

    @BeforeEach
    void setup() {
        importerProperties = new ImporterProperties();
        parser = new AccountBalanceLineParserV1(importerProperties);
    }

    @DisplayName("Parse account balance line")
    @ParameterizedTest(name = "from \"{0}\"")
    @CsvSource(
            value = {
                "'0,0,123,700';false;0;123;700",
                "' 0,0,123,700';false;0;123;700",
                "'0, 0,123,700';false;0;123;700",
                "'0,0, 123,700';false;0;123;700",
                "'0,0,123, 700';false;0;123;700",
                "'0,0,123,700 ';false;0;123;700",
                "'1,0,123,700';true;;;",
                "'x,0,123,700';true;;;",
                "'0,x,123,700';true;;;",
                "'0,0,x,700';true;;;",
                "'0,0,123,a00';true;;;",
                "'1000000000000000000000000000,0,123,700';true;;;",
                "'0,1000000000000000000000000000,123,700';true;;;",
                "'0,0,1000000000000000000000000000,700';true;;;",
                "'0,0,123,1000000000000000000000000000';true;;;",
                "'-1,0,123,700';true;;;",
                "'0,-1,123,700';true;;;",
                "'0,0,-1,700';true;;;",
                "'0,0,123,-1';true;;;",
                "'foobar';true;;;",
                "'';true;;;",
                ";true;;;"
            },
            delimiter = ';')
    void parse(String line, boolean expectThrow, Long expectedRealm, Long expectedAccount, Long expectedBalance) {
        if (!expectThrow) {
            AccountBalance accountBalance = parser.parse(line, TIMESTAMP);
            var id = accountBalance.getId();

            assertThat(accountBalance.getBalance()).isEqualTo(expectedBalance);
            assertThat(id).isNotNull();
            assertThat(id.getAccountId().getShard()).isEqualTo(importerProperties.getShard());
            assertThat(id.getAccountId().getRealm()).isEqualTo(expectedRealm);
            assertThat(id.getAccountId().getNum()).isEqualTo(expectedAccount);
            assertThat(id.getConsensusTimestamp()).isEqualTo(TIMESTAMP);
        } else {
            assertThrows(InvalidDatasetException.class, () -> {
                parser.parse(line, TIMESTAMP);
            });
        }
    }

    @Test
    void parseNullLine() {
        assertThrows(InvalidDatasetException.class, () -> {
            parser.parse(null, TIMESTAMP);
        });
    }
}
