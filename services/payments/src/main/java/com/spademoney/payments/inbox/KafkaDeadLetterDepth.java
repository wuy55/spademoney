package com.spademoney.payments.inbox;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import com.spademoney.payments.ledger.LedgerProperties;

/**
 * Dead-letter depth read from the broker's own offsets.
 *
 * <h2>Depth is latest minus earliest, summed over partitions</h2>
 * That is an exact record count here, not an estimate, because the
 * dead-letter producer is not transactional: there are no commit markers on
 * the topic taking up offsets. What it counts is what the broker still
 * retains. A record aged out by retention stops counting, which is one more
 * reason a parked record is a finding to act on, not one to leave.
 *
 * <h2>Bounded, like the HTTP checks</h2>
 * The admin client's default API timeout is a minute. Reconciliation runs on a
 * one-minute timer, so a broker outage would turn every run into a minute-long
 * hang that reports nothing. Every call here gives up after
 * {@link #TIMEOUT} and throws, and the check reports that it could not read
 * the topic.
 *
 * <h2>Lazy, so no context needs a broker to start</h2>
 * The client is created on the first call, never in the constructor. Every
 * Payments test context builds this bean, and almost none of them has a
 * broker. Once created it is kept, so it is not rebuilt (and its config not
 * re-logged) on every run, and it is closed with the context.
 */
@Component
class KafkaDeadLetterDepth implements DeadLetterDepth, DisposableBean {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final KafkaAdmin kafkaAdmin;
    private final String topic;

    private Admin admin;

    KafkaDeadLetterDepth(KafkaAdmin kafkaAdmin, LedgerProperties properties) {
        this.kafkaAdmin = kafkaAdmin;
        this.topic = properties.deadLetterTopic();
    }

    @Override
    public long depth() {
        try {
            Admin client = admin();
            TopicDescription description;
            try {
                description = client.describeTopics(List.of(topic)).topicNameValues().get(topic)
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                    // Payments declares the topic at startup, and nothing can be
                    // parked on a topic that does not exist.
                    return 0;
                }
                throw e;
            }

            Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            description.partitions().forEach(p -> {
                TopicPartition tp = new TopicPartition(topic, p.partition());
                earliest.put(tp, OffsetSpec.earliest());
                latest.put(tp, OffsetSpec.latest());
            });

            Map<TopicPartition, ListOffsetsResultInfo> start = client.listOffsets(earliest).all()
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Map<TopicPartition, ListOffsetsResultInfo> end = client.listOffsets(latest).all()
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            long depth = 0;
            for (TopicPartition tp : latest.keySet()) {
                depth += end.get(tp).offset() - start.get(tp).offset();
            }
            return depth;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading " + topic, e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("broker did not answer within " + TIMEOUT.toSeconds() + "s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        }
    }

    private synchronized Admin admin() {
        if (admin == null) {
            Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
            int millis = (int) TIMEOUT.toMillis();
            config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, millis);
            config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, millis);
            admin = Admin.create(config);
        }
        return admin;
    }

    @Override
    public synchronized void destroy() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(1));
        }
    }
}
