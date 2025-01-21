/*
 * Copyright (C) 2019-2025 Hedera Hashgraph, LLC
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

package com.hedera.mirror.grpc.service;

import static com.hedera.mirror.common.util.DomainUtils.NANOS_PER_SECOND;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.mirror.common.domain.entity.Entity;
import com.hedera.mirror.common.domain.entity.EntityId;
import com.hedera.mirror.common.domain.entity.EntityType;
import com.hedera.mirror.common.domain.topic.TopicMessage;
import com.hedera.mirror.common.util.DomainUtils;
import com.hedera.mirror.grpc.GrpcIntegrationTest;
import com.hedera.mirror.grpc.GrpcProperties;
import com.hedera.mirror.grpc.domain.ReactiveDomainBuilder;
import com.hedera.mirror.grpc.domain.TopicMessageFilter;
import com.hedera.mirror.grpc.exception.EntityNotFoundException;
import com.hedera.mirror.grpc.listener.ListenerProperties;
import com.hedera.mirror.grpc.listener.TopicListener;
import com.hedera.mirror.grpc.repository.EntityRepository;
import com.hedera.mirror.grpc.retriever.RetrieverProperties;
import com.hedera.mirror.grpc.retriever.TopicMessageRetriever;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.Resource;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class TopicMessageServiceTest extends GrpcIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10L);
    private static final EntityId TOPIC_ID = EntityId.of(100L);

    private final long now = DomainUtils.now();
    private final long future = now + 30L * NANOS_PER_SECOND;

    @Resource
    private TopicMessageService topicMessageService;

    @Autowired
    private ReactiveDomainBuilder domainBuilder;

    @Resource
    private GrpcProperties grpcProperties;

    @Resource
    private ListenerProperties listenerProperties;

    @Resource
    private RetrieverProperties retrieverProperties;

    @BeforeEach
    void setup() {
        listenerProperties.setEnabled(true);
        domainBuilder.entity().block();
    }

    @AfterEach
    void after() {
        listenerProperties.setEnabled(false);
    }

    @Test
    void invalidFilter() {
        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(-1)
                .topicId(null)
                .limit(-1)
                .build();

        assertThatThrownBy(() -> topicMessageService.subscribeTopic(filter))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("limit: must be greater than or equal to 0")
                .hasMessageContaining("startTime: must be greater than or equal to 0")
                .hasMessageContaining("topicId: must not be null");
    }

    @Test
    void endTimeBeforeStartTime() {
        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(now)
                .endTime(now - 86400 * NANOS_PER_SECOND)
                .topicId(TOPIC_ID)
                .build();

        assertThatThrownBy(() -> topicMessageService.subscribeTopic(filter))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("End time must be after start time");
    }

    @Test
    void endTimeEqualsStartTime() {
        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(now)
                .endTime(now)
                .topicId(TOPIC_ID)
                .build();

        assertThatThrownBy(() -> topicMessageService.subscribeTopic(filter))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("End time must be after start time");
    }

    @Test
    void startTimeAfterNow() {
        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(now + 60 * 60 * NANOS_PER_SECOND)
                .topicId(TOPIC_ID)
                .build();

        assertThatThrownBy(() -> topicMessageService.subscribeTopic(filter))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("Start time must be before the current time");
    }

    @Test
    void topicNotFound() {
        TopicMessageFilter filter =
                TopicMessageFilter.builder().topicId(EntityId.of(999L)).build();

        StepVerifier.withVirtualTime(() -> topicMessageService.subscribeTopic(filter))
                .thenAwait(WAIT)
                .expectError(EntityNotFoundException.class)
                .verify(WAIT);
    }

    @Test
    void topicNotFoundWithCheckTopicExistsFalse() {
        grpcProperties.setCheckTopicExists(false);
        TopicMessageFilter filter =
                TopicMessageFilter.builder().topicId(EntityId.of(999L)).build();

        StepVerifier.withVirtualTime(() -> topicMessageService.subscribeTopic(filter))
                .expectSubscription()
                .expectNoEvent(WAIT)
                .thenCancel()
                .verify(WAIT);

        grpcProperties.setCheckTopicExists(true);
    }

    @Test
    void invalidTopic() {
        domainBuilder.entity(e -> e.type(EntityType.ACCOUNT).num(100L).id(100L)).block();
        TopicMessageFilter filter =
                TopicMessageFilter.builder().topicId(TOPIC_ID).build();

        StepVerifier.withVirtualTime(() -> topicMessageService.subscribeTopic(filter))
                .thenAwait(WAIT)
                .expectError(IllegalArgumentException.class)
                .verify(Duration.ofMillis(100));
    }

    @Test
    void noMessages() {
        TopicMessageFilter filter =
                TopicMessageFilter.builder().topicId(TOPIC_ID).build();

        StepVerifier.withVirtualTime(() -> topicMessageService.subscribeTopic(filter))
                .thenAwait(WAIT)
                .expectNextCount(0L)
                .thenCancel()
                .verify(WAIT);
    }

    @Test
    void noMessagesWithPastEndTime() {
        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(0)
                .endTime(1L)
                .topicId(TOPIC_ID)
                .build();

        StepVerifier.withVirtualTime(() -> topicMessageService.subscribeTopic(filter))
                .thenAwait(WAIT)
                .expectNextCount(0L)
                .expectComplete()
                .verify(WAIT);
    }

    @Test
    void noMessagesWithFutureEndTime() {
        long endTime = now + 250_000_000L;

        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(now)
                .endTime(endTime)
                .topicId(TOPIC_ID)
                .build();

        topicMessageService
                .subscribeTopic(filter)
                .map(TopicMessage::getSequenceNumber)
                .as(StepVerifier::create)
                .expectNextCount(0L)
                .expectComplete()
                .verify(WAIT);
    }

    @Test
    void historicalMessages() {
        TopicMessage topicMessage1 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage2 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage3 = domainBuilder.topicMessage().block();

        TopicMessageFilter filter =
                TopicMessageFilter.builder().startTime(0).topicId(TOPIC_ID).build();

        StepVerifier.withVirtualTime(() -> topicMessageService.subscribeTopic(filter))
                .thenAwait(WAIT)
                .expectNext(topicMessage1, topicMessage2, topicMessage3)
                .thenCancel()
                .verify(WAIT);
    }

    @Test
    void historicalMessagesWithEndTimeAfter() {
        TopicMessage topicMessage1 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage2 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage3 = domainBuilder.topicMessage().block();

        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(0)
                .endTime(topicMessage3.getConsensusTimestamp() + 1)
                .topicId(TOPIC_ID)
                .build();

        StepVerifier.withVirtualTime(() -> topicMessageService.subscribeTopic(filter))
                .thenAwait(WAIT)
                .expectNext(topicMessage1, topicMessage2, topicMessage3)
                .expectComplete()
                .verify(Duration.ofMillis(500));
    }

    @Test
    void historicalMessagesWithEndTimeEquals() {
        TopicMessage topicMessage1 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage2 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage3 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage4 = domainBuilder.topicMessage().block();

        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(0)
                .endTime(topicMessage4.getConsensusTimestamp())
                .topicId(TOPIC_ID)
                .build();

        StepVerifier.withVirtualTime(() -> topicMessageService.subscribeTopic(filter))
                .thenAwait(WAIT)
                .expectNext(topicMessage1, topicMessage2, topicMessage3)
                .expectComplete()
                .verify(WAIT);
    }

    @Test
    void historicalMessagesWithEndTimeExceedsPageSize() {
        int oldMaxPageSize = retrieverProperties.getMaxPageSize();
        retrieverProperties.setMaxPageSize(1);

        TopicMessage topicMessage1 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage2 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage3 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage4 = domainBuilder.topicMessage().block();

        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(0)
                .endTime(topicMessage4.getConsensusTimestamp())
                .topicId(TOPIC_ID)
                .build();

        StepVerifier.withVirtualTime(() -> topicMessageService.subscribeTopic(filter))
                .thenAwait(WAIT)
                .expectNext(topicMessage1)
                .expectNext(topicMessage2)
                .expectNext(topicMessage3)
                .expectComplete()
                .verify(WAIT);

        retrieverProperties.setMaxPageSize(oldMaxPageSize);
    }

    @Test
    void historicalMessagesWithLimit() {
        TopicMessage topicMessage1 = domainBuilder.topicMessage().block();
        TopicMessage topicMessage2 = domainBuilder.topicMessage().block();
        domainBuilder.topicMessage().block();

        TopicMessageFilter filter = TopicMessageFilter.builder()
                .limit(2)
                .startTime(0)
                .topicId(TOPIC_ID)
                .build();

        StepVerifier.withVirtualTime(() -> topicMessageService.subscribeTopic(filter))
                .thenAwait(WAIT)
                .expectNext(topicMessage1)
                .expectNext(topicMessage2)
                .expectComplete()
                .verify(WAIT);
    }

    @Test
    void incomingMessages() {
        TopicMessageFilter filter =
                TopicMessageFilter.builder().startTime(0).topicId(TOPIC_ID).build();

        StepVerifier.withVirtualTime(
                        () -> topicMessageService.subscribeTopic(filter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .then(() -> domainBuilder.topicMessages(3, future).blockLast())
                .expectNext(1L, 2L, 3L)
                .thenCancel()
                .verify(WAIT);
    }

    @Test
    void incomingMessagesWithLimit() {
        TopicMessageFilter filter = TopicMessageFilter.builder()
                .limit(2)
                .startTime(0)
                .topicId(TOPIC_ID)
                .build();

        StepVerifier.withVirtualTime(
                        () -> topicMessageService.subscribeTopic(filter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .then(() -> domainBuilder.topicMessages(3, future).blockLast())
                .expectNext(1L, 2L)
                .expectComplete()
                .verify(WAIT);
    }

    @Test
    void incomingMessagesWithEndTimeBefore() {
        long endTime = now + 500_000_000L;
        Flux<TopicMessage> generator = domainBuilder.topicMessages(2, endTime - 2L);

        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(0)
                .endTime(endTime)
                .topicId(TOPIC_ID)
                .build();

        topicMessageService
                .subscribeTopic(filter)
                .map(TopicMessage::getSequenceNumber)
                .as(StepVerifier::create)
                .thenAwait(Duration.ofMillis(50))
                .then(generator::blockLast)
                .expectNext(1L, 2L)
                .expectComplete()
                .verify(Duration.ofMillis(1000));
    }

    @Test
    void incomingMessagesWithEndTimeEquals() {
        Flux<TopicMessage> generator = domainBuilder.topicMessages(3, future - 2);

        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(0)
                .endTime(future)
                .topicId(TOPIC_ID)
                .build();

        StepVerifier.withVirtualTime(
                        () -> topicMessageService.subscribeTopic(filter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .then(generator::blockLast)
                .expectNext(1L, 2L)
                .expectComplete()
                .verify(WAIT);
    }

    @Test
    void bothMessages() {
        domainBuilder.topicMessages(3, now).blockLast();

        TopicMessageFilter filter = TopicMessageFilter.builder()
                .limit(5)
                .startTime(0)
                .topicId(TOPIC_ID)
                .build();

        StepVerifier.withVirtualTime(
                        () -> topicMessageService.subscribeTopic(filter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .then(() -> domainBuilder.topicMessages(3, future).blockLast())
                .expectNext(1L, 2L, 3L, 4L, 5L)
                .expectComplete()
                .verify(WAIT);
    }

    @Test
    void bothMessagesWithTopicId() {
        domainBuilder.entity(e -> e.num(1L).id(1L)).block();
        domainBuilder.entity(e -> e.num(2L).id(2L)).block();
        domainBuilder
                .topicMessage(t -> t.topicId(EntityId.of(1L)).sequenceNumber(1))
                .block();
        domainBuilder
                .topicMessage(t -> t.topicId(EntityId.of(2L)).sequenceNumber(1))
                .block();

        Flux<TopicMessage> generator = Flux.concat(
                domainBuilder.topicMessage(
                        t -> t.topicId(EntityId.of(1L)).sequenceNumber(2).consensusTimestamp(future + 1)),
                domainBuilder.topicMessage(
                        t -> t.topicId(EntityId.of(2L)).sequenceNumber(2).consensusTimestamp(future + 2)),
                domainBuilder.topicMessage(
                        t -> t.topicId(EntityId.of(3L)).sequenceNumber(1).consensusTimestamp(future + 3)));

        TopicMessageFilter filter = TopicMessageFilter.builder()
                .startTime(0)
                .topicId(EntityId.of(1L))
                .build();

        StepVerifier.withVirtualTime(
                        () -> topicMessageService.subscribeTopic(filter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .then(generator::blockLast)
                .expectNext(1L, 2L)
                .thenCancel()
                .verify(Duration.ofMillis(500));
    }

    @Test
    void duplicateMessages() {
        TopicListener topicListener = Mockito.mock(TopicListener.class);
        EntityRepository entityRepository = Mockito.mock(EntityRepository.class);
        TopicMessageRetriever topicMessageRetriever = Mockito.mock(TopicMessageRetriever.class);
        topicMessageService = new TopicMessageServiceImpl(
                new GrpcProperties(),
                topicListener,
                entityRepository,
                topicMessageRetriever,
                new SimpleMeterRegistry());

        TopicMessageFilter retrieverFilter =
                TopicMessageFilter.builder().startTime(0).topicId(TOPIC_ID).build();

        Mockito.when(entityRepository.findById(retrieverFilter.getTopicId().getId()))
                .thenReturn(optionalEntity());

        Mockito.when(topicMessageRetriever.retrieve(
                        ArgumentMatchers.isA(TopicMessageFilter.class), ArgumentMatchers.eq(true)))
                .thenReturn(Flux.just(topicMessage(1, 0), topicMessage(1, 1), topicMessage(2, 2), topicMessage(1, 3)));

        Mockito.when(topicListener.listen(ArgumentMatchers.any())).thenReturn(Flux.empty());

        StepVerifier.withVirtualTime(() ->
                        topicMessageService.subscribeTopic(retrieverFilter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .expectNext(1L, 2L)
                .expectComplete()
                .verify(WAIT);
    }

    @Test
    void missingMessages() {
        TopicListener topicListener = Mockito.mock(TopicListener.class);
        EntityRepository entityRepository = Mockito.mock(EntityRepository.class);
        TopicMessageRetriever topicMessageRetriever = Mockito.mock(TopicMessageRetriever.class);
        topicMessageService = new TopicMessageServiceImpl(
                new GrpcProperties(),
                topicListener,
                entityRepository,
                topicMessageRetriever,
                new SimpleMeterRegistry());

        TopicMessageFilter filter =
                TopicMessageFilter.builder().startTime(0).topicId(TOPIC_ID).build();

        TopicMessage beforeMissing = topicMessage(1);
        TopicMessage afterMissing = topicMessage(4);

        Mockito.when(entityRepository.findById(filter.getTopicId().getId())).thenReturn(optionalEntity());
        Mockito.when(topicMessageRetriever.retrieve(filter, true)).thenReturn(Flux.empty());
        Mockito.when(topicListener.listen(filter)).thenReturn(Flux.just(beforeMissing, afterMissing));
        Mockito.when(topicMessageRetriever.retrieve(
                        ArgumentMatchers.argThat(t -> t.getLimit() == 2
                                && t.getStartTime() == beforeMissing.getConsensusTimestamp() + 1
                                && t.getEndTime() == afterMissing.getConsensusTimestamp()),
                        ArgumentMatchers.eq(false)))
                .thenReturn(Flux.just(topicMessage(2), topicMessage(3)));

        StepVerifier.withVirtualTime(
                        () -> topicMessageService.subscribeTopic(filter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .expectNext(1L, 2L, 3L, 4L)
                .thenCancel()
                .verify(WAIT);
    }

    @Test
    void missingMessagesFromListenerAllRetrieved() {
        TopicMessageFilter filter =
                TopicMessageFilter.builder().startTime(0).topicId(TOPIC_ID).build();

        missingMessagesFromListenerTest(filter, Flux.just(topicMessage(5), topicMessage(6), topicMessage(7)));

        StepVerifier.withVirtualTime(
                        () -> topicMessageService.subscribeTopic(filter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .expectNext(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
                .thenCancel()
                .verify(WAIT);
    }

    @Test
    void missingMessagesFromListenerSomeRetrieved() {
        TopicMessageFilter filter =
                TopicMessageFilter.builder().startTime(0).topicId(TOPIC_ID).build();

        missingMessagesFromListenerTest(filter, Flux.just(topicMessage(5), topicMessage(6)));

        StepVerifier.withVirtualTime(
                        () -> topicMessageService.subscribeTopic(filter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .expectNext(1L, 2L, 3L, 4L, 5L, 6L)
                .expectError(IllegalStateException.class)
                .verify(WAIT);
    }

    @Test
    void missingMessagesFromListenerNoneRetrieved() {
        TopicMessageFilter filter =
                TopicMessageFilter.builder().startTime(0).topicId(TOPIC_ID).build();

        missingMessagesFromListenerTest(filter, Flux.empty());

        StepVerifier.withVirtualTime(
                        () -> topicMessageService.subscribeTopic(filter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .expectNext(1L, 2L, 3L, 4L)
                .expectError(IllegalStateException.class)
                .verify(WAIT);
    }

    @Test
    void missingMessagesFromRetrieverAndListener() {
        TopicListener topicListener = Mockito.mock(TopicListener.class);
        EntityRepository entityRepository = Mockito.mock(EntityRepository.class);
        TopicMessageRetriever topicMessageRetriever = Mockito.mock(TopicMessageRetriever.class);
        topicMessageService = new TopicMessageServiceImpl(
                new GrpcProperties(),
                topicListener,
                entityRepository,
                topicMessageRetriever,
                new SimpleMeterRegistry());

        TopicMessageFilter retrieverFilter =
                TopicMessageFilter.builder().startTime(0).topicId(TOPIC_ID).build();

        TopicMessage retrieved1 = topicMessage(1);
        TopicMessage retrieved2 = topicMessage(2);

        TopicMessage beforeMissing1 = topicMessage(3);
        TopicMessage beforeMissing2 = topicMessage(4);
        TopicMessage afterMissing1 = topicMessage(8);
        TopicMessage afterMissing2 = topicMessage(9);
        TopicMessage afterMissing3 = topicMessage(10);

        Mockito.when(entityRepository.findById(retrieverFilter.getTopicId().getId()))
                .thenReturn(optionalEntity());

        TopicMessageFilter listenerFilter = TopicMessageFilter.builder()
                .startTime(retrieved2.getConsensusTimestamp())
                .build();

        Mockito.when(topicListener.listen(
                        ArgumentMatchers.argThat(l -> l.getStartTime() == listenerFilter.getStartTime())))
                .thenReturn(Flux.just(beforeMissing1, beforeMissing2, afterMissing1, afterMissing2, afterMissing3));

        Mockito.when(topicMessageRetriever.retrieve(
                        ArgumentMatchers.isA(TopicMessageFilter.class), ArgumentMatchers.eq(true)))
                .thenReturn(Flux.just(retrieved1));
        var missing = Flux.just(topicMessage(5), topicMessage(6), topicMessage(7));
        Mockito.when(topicMessageRetriever.retrieve(
                        ArgumentMatchers.isA(TopicMessageFilter.class), ArgumentMatchers.eq(false)))
                .thenReturn(Flux.just(retrieved2))
                .thenReturn(missing);

        StepVerifier.withVirtualTime(() ->
                        topicMessageService.subscribeTopic(retrieverFilter).map(TopicMessage::getSequenceNumber))
                .thenAwait(WAIT)
                .expectNext(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
                .expectComplete()
                .verify(WAIT);
    }

    private void missingMessagesFromListenerTest(TopicMessageFilter filter, Flux<TopicMessage> missingMessages) {
        TopicListener topicListener = Mockito.mock(TopicListener.class);
        EntityRepository entityRepository = Mockito.mock(EntityRepository.class);
        TopicMessageRetriever topicMessageRetriever = Mockito.mock(TopicMessageRetriever.class);
        topicMessageService = new TopicMessageServiceImpl(
                new GrpcProperties(),
                topicListener,
                entityRepository,
                topicMessageRetriever,
                new SimpleMeterRegistry());

        // historic messages
        TopicMessage retrieved1 = topicMessage(1);
        TopicMessage retrieved2 = topicMessage(2);

        // incoming messages before gap
        TopicMessage beforeMissing1 = topicMessage(3);
        TopicMessage beforeMissing2 = topicMessage(4);

        // incoming messages after gap
        TopicMessage afterMissing1 = topicMessage(8);
        TopicMessage afterMissing2 = topicMessage(9);
        TopicMessage afterMissing3 = topicMessage(10);

        // mock entity type check
        Mockito.when(entityRepository.findById(filter.getTopicId().getId())).thenReturn(optionalEntity());
        Mockito.when(topicMessageRetriever.retrieve(filter, true)).thenReturn(Flux.just(retrieved1, retrieved2));

        TopicMessageFilter listenerFilter = TopicMessageFilter.builder()
                .startTime(beforeMissing1.getConsensusTimestamp())
                .build();

        Mockito.when(topicListener.listen(
                        ArgumentMatchers.argThat(l -> l.getStartTime() == listenerFilter.getStartTime())))
                .thenReturn(Flux.just(beforeMissing1, beforeMissing2, afterMissing1, afterMissing2, afterMissing3));
        Mockito.when(topicMessageRetriever.retrieve(
                        ArgumentMatchers.argThat(t -> t.getLimit() == 3
                                && t.getStartTime() == beforeMissing2.getConsensusTimestamp() + 1
                                && t.getEndTime() == afterMissing1.getConsensusTimestamp()),
                        ArgumentMatchers.eq(false)))
                .thenReturn(missingMessages);
    }

    private TopicMessage topicMessage(long sequenceNumber) {
        return topicMessage(sequenceNumber, sequenceNumber);
    }

    private TopicMessage topicMessage(long sequenceNumber, long consensusTimestamp) {
        return TopicMessage.builder()
                .consensusTimestamp(consensusTimestamp)
                .sequenceNumber(sequenceNumber)
                .message(new byte[] {0, 1, 2})
                .runningHash(new byte[] {3, 4, 5})
                .topicId(EntityId.of(100L))
                .runningHashVersion(2)
                .build();
    }

    private Optional<Entity> optionalEntity() {
        return Optional.of(Entity.builder().type(EntityType.TOPIC).memo("memo").build());
    }
}
