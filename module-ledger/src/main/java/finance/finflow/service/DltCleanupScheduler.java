package finance.finflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// The other half of DLT cleanup, alongside DltAdminService. A retry/delete click can only remove a
// message from Kafka immediately if it's already the oldest one on its partition - anything
// blocked behind an earlier, still-unresolved message just gets marked resolved and waits. This
// runs on its own slower schedule, walks each DLT topic from the front, and physically purges every
// consecutive run of resolved messages it finds, so blocked ones eventually get cleaned up too once
// whatever was ahead of them is dealt with.
@Slf4j
@Component
@RequiredArgsConstructor
public class DltCleanupScheduler {

    private static final String DLT_SUFFIX = ".DLT";
    // The same three topics the frontend's DLT admin dropdown offers.
    private static final List<String> DLT_BASE_TOPICS = List.of("payment-completed", "payment-failed", "wallet-credited");

    private final DltAdminService dltAdminService;

    @Scheduled(fixedDelay = 300000)
    public void sweep() {
        for (String baseTopic : DLT_BASE_TOPICS) {
            sweepTopic(baseTopic);
        }
    }

    private void sweepTopic(String baseTopic) {
        String dltTopic = baseTopic + DLT_SUFFIX;

        try (KafkaConsumer<String, String> consumer = dltAdminService.newStandaloneConsumer()) {
            List<TopicPartition> partitions = dltAdminService.partitionsFor(dltTopic, consumer);
            if (partitions.isEmpty()) {
                return;
            }

            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            for (TopicPartition partition : partitions) {
                long start = beginningOffsets.get(partition);
                long end = endOffsets.get(partition); // exclusive - the next offset that will be written
                if (start >= end) {
                    continue; // partition is empty, nothing to sweep
                }
                dltAdminService.sweepPartition(baseTopic, dltTopic, partition, start, end);
            }
        }
    }
}
