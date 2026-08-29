package com.spademoney.payments.inbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.spademoney.payments.ledger.LedgerProperties;

/**
 * Declares the dead-letter topic. Payments owns this one -- it is the record of
 * what this consumer could not process -- whereas the events topic belongs to
 * the Ledger and is only read here.
 *
 * Same partition count as the source topic, because the recoverer sends a
 * dead-lettered record to the same partition NUMBER it failed on. Fewer
 * partitions here and a record failing on partition 2 would have nowhere to go.
 */
@Configuration(proxyBeanMethods = false)
class DeadLetterTopicConfig {

    @Bean
    NewTopic ledgerEventsDeadLetterTopic(LedgerProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
