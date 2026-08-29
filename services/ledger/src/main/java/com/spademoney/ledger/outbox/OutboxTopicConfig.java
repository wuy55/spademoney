package com.spademoney.ledger.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the events topic rather than relying on broker auto-creation.
 *
 * Auto-created topics take the broker's defaults, which is how a topic ends up
 * with a partition count nobody chose and an ordering guarantee nobody checked.
 * Declaring it makes the number visible next to the code whose correctness
 * depends on it.
 *
 * <h2>Three partitions, and why that is not a throughput decision</h2>
 * One partition would give total ordering for free and would also make the
 * partition key in {@link KafkaEventPublisher} decorative -- the design would
 * look correct while being untested. Three partitions means events for different
 * aggregates genuinely land in different places, so per-aggregate ordering is
 * something the key actually has to earn. Kafka orders within a partition only;
 * across the topic there is no order, and no consumer here assumes one.
 *
 * If the broker is unreachable at startup this is a logged warning, not a
 * failure. The Ledger's job is to keep taking money movements even when the
 * event pipe is down -- that is precisely what the outbox buys.
 */
@Configuration(proxyBeanMethods = false)
class OutboxTopicConfig {

    @Bean
    NewTopic ledgerEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
