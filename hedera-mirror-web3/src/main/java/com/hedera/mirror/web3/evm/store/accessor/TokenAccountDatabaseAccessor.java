/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.hedera.mirror.web3.evm.store.accessor;

import com.hedera.mirror.common.domain.token.AbstractTokenAccount;
import com.hedera.mirror.common.domain.token.TokenAccount;
import com.hedera.mirror.web3.evm.store.DatabaseBackedStateFrame.DatabaseAccessIncorrectKeyTypeException;
import com.hedera.mirror.web3.repository.TokenAccountRepository;
import jakarta.inject.Named;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Named
@RequiredArgsConstructor
public class TokenAccountDatabaseAccessor extends DatabaseAccessor<Object, TokenAccount> {

    private final TokenAccountRepository tokenAccountRepository;

    @Override
    public @NonNull Optional<TokenAccount> get(@NonNull Object key, final Optional<Long> timestamp) {
        if (key instanceof AbstractTokenAccount.Id id) {
            return timestamp
                    .map(t -> tokenAccountRepository.findByIdAndTimestamp(id.getAccountId(), id.getTokenId(), t))
                    .orElseGet(() -> tokenAccountRepository.findById(id));
        }
        throw new DatabaseAccessIncorrectKeyTypeException("Accessor for class %s failed to fetch by key of type %s"
                .formatted(TokenAccount.class.getTypeName(), key.getClass().getTypeName()));
    }
}
