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

package com.hedera.mirror.importer.config;

import static com.hedera.mirror.common.util.DomainUtils.convertToNanosMax;
import static com.hedera.mirror.importer.TestUtils.plus;
import static com.hedera.mirror.importer.config.DateRangeCalculator.DateRangeFilter;
import static com.hedera.mirror.importer.config.DateRangeCalculator.STARTUP_TIME;
import static com.hedera.mirror.importer.domain.StreamFilename.FileType.DATA;
import static org.apache.commons.lang3.ObjectUtils.max;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.hedera.mirror.common.domain.StreamFile;
import com.hedera.mirror.common.domain.StreamType;
import com.hedera.mirror.importer.ImporterProperties;
import com.hedera.mirror.importer.domain.StreamFilename;
import com.hedera.mirror.importer.downloader.CommonDownloaderProperties;
import com.hedera.mirror.importer.downloader.DownloaderProperties;
import com.hedera.mirror.importer.downloader.balance.BalanceDownloaderProperties;
import com.hedera.mirror.importer.downloader.record.RecordDownloaderProperties;
import com.hedera.mirror.importer.exception.InvalidConfigurationException;
import com.hedera.mirror.importer.repository.AccountBalanceFileRepository;
import com.hedera.mirror.importer.repository.RecordFileRepository;
import com.hedera.mirror.importer.repository.StreamFileRepository;
import com.hedera.mirror.importer.util.Utility;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DateRangeCalculatorTest {

    private final Map<StreamType, StreamFileRepository<?, ?>> streamFileRepositories = new EnumMap<>(StreamType.class);

    @Mock
    private AccountBalanceFileRepository accountBalanceFileRepository;

    @Mock
    private RecordFileRepository recordFileRepository;

    private ImporterProperties importerProperties;
    private List<DownloaderProperties> downloaderPropertiesList;
    private DateRangeCalculator dateRangeCalculator;

    @BeforeEach
    void setUp() {
        importerProperties = new ImporterProperties();
        importerProperties.setNetwork(ImporterProperties.HederaNetwork.TESTNET);
        var commonDownloaderProperties = new CommonDownloaderProperties(importerProperties);
        var balanceDownloaderProperties = new BalanceDownloaderProperties(commonDownloaderProperties);
        var recordDownloaderProperties = new RecordDownloaderProperties(commonDownloaderProperties);
        downloaderPropertiesList = List.of(balanceDownloaderProperties, recordDownloaderProperties);
        dateRangeCalculator =
                new DateRangeCalculator(importerProperties, accountBalanceFileRepository, recordFileRepository);

        balanceDownloaderProperties.setEnabled(true);
        recordDownloaderProperties.setEnabled(true);

        streamFileRepositories.putIfAbsent(StreamType.BALANCE, accountBalanceFileRepository);
        streamFileRepositories.putIfAbsent(StreamType.RECORD, recordFileRepository);
    }

    @Test
    void notSetAndDatabaseEmpty() {
        var expectedDate = STARTUP_TIME;
        var expectedFilter = new DateRangeFilter(expectedDate, null);
        for (var downloaderProperties : downloaderPropertiesList) {
            var streamType = downloaderProperties.getStreamType();
            assertThat(dateRangeCalculator.getLastStreamFile(streamType))
                    .isEqualTo(streamFile(streamType, expectedDate, true));
            assertThat(dateRangeCalculator.getFilter(streamType)).isEqualTo(expectedFilter);
        }
    }

    @Test
    void notSetAndDatabaseNotEmpty() {
        var past = STARTUP_TIME.minusSeconds(100);
        streamFileRepositories.forEach((streamType, repository) ->
                doReturn(streamFile(streamType, past, false)).when(repository).findLatest());
        verifyWhenLastStreamFileFromDatabase(past);
    }

    @Test
    void startDateNotSetAndEndDateAfterLongMaxAndDatabaseNotEmpty() {
        var past = STARTUP_TIME.minusSeconds(100);
        importerProperties.setEndDate(Utility.MAX_INSTANT_LONG.plusNanos(1));
        streamFileRepositories.forEach((streamType, repository) ->
                doReturn(streamFile(streamType, past, false)).when(repository).findLatest());
        verifyWhenLastStreamFileFromDatabase(past);
    }

    @Test
    void startDateSetAndDatabaseEmpty() {
        var startDate = STARTUP_TIME.plusSeconds(10L);
        importerProperties.setStartDate(startDate);
        var expectedFilter = new DateRangeFilter(importerProperties.getStartDate(), null);
        var expectedDate = importerProperties.getStartDate();
        for (var downloaderProperties : downloaderPropertiesList) {
            StreamType streamType = downloaderProperties.getStreamType();
            assertThat(dateRangeCalculator.getLastStreamFile(streamType))
                    .isEqualTo(streamFile(streamType, expectedDate, true));
            assertThat(dateRangeCalculator.getFilter(streamType)).isEqualTo(expectedFilter);
        }
    }

    @ParameterizedTest(name = "startDate {0}ns before application status, endDate")
    @ValueSource(longs = {0, 1})
    void startDateNotAfterDatabase(long nanos) {
        var past = STARTUP_TIME.minusSeconds(100);
        importerProperties.setStartDate(past.minusNanos(nanos));
        streamFileRepositories.forEach((streamType, repository) ->
                doReturn(streamFile(streamType, past, false)).when(repository).findLatest());
        verifyWhenLastStreamFileFromDatabase(past);
    }

    @ParameterizedTest(name = "startDate is {0}ns after application status")
    @ValueSource(longs = {1, 2_000_000_000L, 200_000_000_000L})
    void startDateAfterDatabase(long diffNanos) {
        var lastFileInstant = Instant.now().minusSeconds(200);
        streamFileRepositories.forEach(
                (streamType, repository) -> doReturn(streamFile(streamType, lastFileInstant, false))
                        .when(repository)
                        .findLatest());

        var startDate = lastFileInstant.plusNanos(diffNanos);
        importerProperties.setStartDate(startDate);
        var effectiveStartDate = max(startDate, lastFileInstant);

        var expectedFilter = new DateRangeFilter(startDate, null);
        for (var downloaderProperties : downloaderPropertiesList) {
            var streamType = downloaderProperties.getStreamType();

            assertThat(dateRangeCalculator.getLastStreamFile(streamType))
                    .isEqualTo(streamFile(streamType, effectiveStartDate, true));
            assertThat(dateRangeCalculator.getFilter(downloaderProperties.getStreamType()))
                    .isEqualTo(expectedFilter);
        }
    }

    @ParameterizedTest(
            name = "startDate {0} endDate {1} database {2} violates (effective) start date <= " + "end date constraint")
    @CsvSource(
            value = {
                "2020-08-18T09:00:05.124Z, 2020-08-18T09:00:05.123Z,",
                "2020-08-18T09:00:04.123Z, 2020-08-18T09:00:05.123Z, 2020-08-18T09:00:05.124Z",
                "2020-08-18T09:00:04.123Z, 2020-08-18T09:00:05.123Z, 2020-08-18T09:00:06.123Z",
                ", 2020-08-18T09:00:05.123Z, 2020-08-19T09:00:05.111Z",
                ", 2020-08-18T09:00:05.123Z,"
            })
    void startDateNotBeforeEndDate(Instant startDate, Instant endDate, Instant lastFileDate) {
        importerProperties.setStartDate(startDate);
        importerProperties.setEndDate(endDate);

        if (lastFileDate != null) {
            streamFileRepositories.forEach(
                    (streamType, repository) -> doReturn(streamFile(streamType, lastFileDate, false))
                            .when(repository)
                            .findLatest());
        }

        for (var downloaderProperties : downloaderPropertiesList) {
            var streamType = downloaderProperties.getStreamType();
            assertThatThrownBy(() -> dateRangeCalculator.getLastStreamFile(streamType))
                    .isInstanceOf(InvalidConfigurationException.class);
        }
    }

    @ParameterizedTest(name = "timestamp {0} does not pass empty filter")
    @ValueSource(longs = {-10L, 0L, 1L, 10L, 8L, 100L})
    void emptyFilter(long timestamp) {
        DateRangeFilter filter = DateRangeFilter.empty();
        assertThat(filter.filter(timestamp)).isFalse();
    }

    @ParameterizedTest(name = "filter [{0}, {1}], timestamp {2}, pass: {3}")
    @CsvSource(
            value = {
                "1, 1, 1, true",
                "1, 10, 1, true",
                "1, 10, 10, true",
                "1, 10, 6, true",
                "1, 10, 0, false",
                "1, 10, 11, false",
                "1, 10, -1, false",
            })
    void filter(long start, long end, long timestamp, boolean expected) {
        var filter = new DateRangeFilter(Instant.ofEpochSecond(0, start), Instant.ofEpochSecond(0, end));
        assertThat(filter.filter(timestamp)).isEqualTo(expected);
    }

    private Optional<StreamFile<?>> streamFile(StreamType streamType, Instant instant, boolean nullConsensusEnd) {
        var streamFile = streamType.newStreamFile();
        long consensusStart = convertToNanosMax(instant);
        streamFile.setConsensusStart(consensusStart);
        if (!nullConsensusEnd) {
            streamFile.setConsensusEnd(plus(consensusStart, streamType.getFileCloseInterval()));
        }
        streamFile.setName(StreamFilename.getFilename(streamType, DATA, instant));
        return Optional.of(streamFile);
    }

    private void verifyWhenLastStreamFileFromDatabase(Instant fileInstant) {
        long expectedConsensusStart = convertToNanosMax(fileInstant);
        var expectedDateRangeFilter = new DateRangeFilter(fileInstant, null);
        for (var downloaderProperties : downloaderPropertiesList) {
            var streamType = downloaderProperties.getStreamType();
            long expectedConsensusEnd = streamType != StreamType.BALANCE
                    ? plus(expectedConsensusStart, streamType.getFileCloseInterval())
                    : expectedConsensusStart;
            assertThat(dateRangeCalculator.getLastStreamFile(streamType))
                    .get()
                    .returns(expectedConsensusStart, StreamFile::getConsensusStart)
                    .returns(expectedConsensusEnd, StreamFile::getConsensusEnd);
            assertThat(dateRangeCalculator.getFilter(streamType)).isEqualTo(expectedDateRangeFilter);
        }
    }
}
