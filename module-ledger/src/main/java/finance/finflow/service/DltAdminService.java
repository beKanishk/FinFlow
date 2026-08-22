package finance.finflow.service;

import finance.finflow.dto.DltMessageDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

// Lets an operator look at and replay messages sitting on a dead-letter topic (the "<topic>.DLT"
// topics created by KafkaConfig's DeadLetterPublishingRecoverer). Every read here uses a
// throwaway KafkaConsumer that is manually "assign"-ed to partitions instead of "subscribe"-ing
// through a consumer group - that means it never commits offsets and never competes with a real
// consumer group, so a dead-lettered message stays put and can be listed/retried as many times as
// needed until someone is happy it's fixed.
@Slf4j
@Service
@RequiredArgsConstructor
public class DltAdminService {

    private static final String DLT_SUFFIX = ".DLT";
    // Server-side ceiling on page size - a client can ask for fewer, but never more than this in
    // one call, so a careless "size=1000000" request can't make this scan the whole topic.
    private static final int MAX_PAGE_SIZE = 200;
    // Safety cap on how many records we're willing to read through to satisfy a page request
    // (e.g. "page=50" on a topic where most of it has already been dealt with) - without this a
    // deep page number would poll forever on a topic that never has that many messages.
    private static final int MAX_RECORDS_SCANNED = 5000;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(3);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private final KafkaTemplate<String, String> kafkaTemplate;

    // page is 0-based, like the pagination used elsewhere in this app (TransactionFilterRequest).
    public List<DltMessageDTO> listMessages(String baseTopic, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int skip = Math.max(page, 0) * pageSize;

        String dltTopic = baseTopic + DLT_SUFFIX;
        List<DltMessageDTO> messages = new ArrayList<>();

        try (KafkaConsumer<String, String> consumer = newStandaloneConsumer()) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(dltTopic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return messages;
            }

            List<TopicPartition> partitions = partitionInfos.stream()
                    .map(info -> new TopicPartition(dltTopic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            int scanned = 0;
            while (messages.size() < pageSize && scanned < MAX_RECORDS_SCANNED) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    break; // caught up to the end of the topic, no more messages to read
                }
                for (ConsumerRecord<String, String> record : records) {
                    scanned++;
                    if (scanned > skip) {
                        messages.add(new DltMessageDTO(record.partition(), record.offset(), record.key(), record.value()));
                        if (messages.size() >= pageSize) {
                            break;
                        }
                    }
                }
            }
        }

        return messages;
    }

    public void retryMessage(String baseTopic, int partition, long offset) {
        String dltTopic = baseTopic + DLT_SUFFIX;
        TopicPartition topicPartition = new TopicPartition(dltTopic, partition);

        ConsumerRecord<String, String> target;
        try (KafkaConsumer<String, String> consumer = newStandaloneConsumer()) {
            consumer.assign(List.of(topicPartition));
            consumer.seek(topicPartition, offset);

            target = null;
            ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
            for (ConsumerRecord<String, String> record : records) {
                if (record.partition() == partition && record.offset() == offset) {
                    target = record;
                    break;
                }
            }
        }

        if (target == null) {
            throw new NoSuchElementException("No message found at " + dltTopic + "[" + partition + "]@" + offset);
        }

        kafkaTemplate.send(baseTopic, target.key(), target.value());
        log.info("Replayed dead-lettered message: dltTopic={}, partition={}, offset={}, republishedTo={}",
                dltTopic, partition, offset, baseTopic);
    }

    private KafkaConsumer<String, String> newStandaloneConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
    }
}
