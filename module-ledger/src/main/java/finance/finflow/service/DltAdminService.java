package finance.finflow.service;

import finance.finflow.dto.DltMessageDTO;
import finance.finflow.module.DltResolution;
import finance.finflow.module.DltResolutionType;
import finance.finflow.repository.DltResolutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.RecordsToDelete;
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
import java.util.concurrent.TimeUnit;

// Lets an operator look at, replay, and discard messages sitting on a dead-letter topic (the
// "<topic>.DLT" topics created by KafkaConfig's DeadLetterPublishingRecoverer).
//
// A message is "resolved" (via retry or delete) the moment an admin acts on it - a DltResolution
// row is written straight away, and listMessages() hides resolved messages from then on regardless
// of whether Kafka itself has actually let go of the record yet. Physically removing a resolved
// message from Kafka happens in one of two ways: immediately, if it's already the oldest surviving
// record on its partition (the only position Kafka can safely delete without also wiping out
// earlier, still-unresolved messages); otherwise DltCleanupScheduler picks it up later once
// everything ahead of it has also been resolved.
//
// Every read here uses a throwaway KafkaConsumer manually "assign"-ed to partitions instead of
// "subscribe"-ing through a consumer group, so it never commits offsets and never competes with a
// real consumer group.
@Slf4j
@Service
@RequiredArgsConstructor
public class DltAdminService {

    private static final String DLT_SUFFIX = ".DLT";
    // Server-side ceiling on page size - a client can ask for fewer, but never more than this in
    // one call, so a careless "size=1000000" request can't make this scan the whole topic.
    private static final int MAX_PAGE_SIZE = 200;
    // Safety cap on how many raw records we're willing to read through to satisfy a page request
    // or a cleanup sweep - without this a deep page number, or a partition with a long run of
    // resolved records, would poll forever.
    static final int MAX_RECORDS_SCANNED = 5000;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(3);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DltResolutionRepository dltResolutionRepository;

    // page is 0-based, like the pagination used elsewhere in this app (TransactionFilterRequest).
    // Paging is applied AFTER filtering out already-resolved messages, so "page=0,size=50" always
    // means "the first 50 messages still needing attention", not "the first 50 raw Kafka records".
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

            // The oldest record still on each partition is the only one that can be deleted right
            // now without also wiping out earlier, unresolved messages.
            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);

            int rawScanned = 0;
            int unresolvedSeen = 0;
            while (messages.size() < pageSize && rawScanned < MAX_RECORDS_SCANNED) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    break; // caught up to the end of the topic, nothing more to read
                }
                for (ConsumerRecord<String, String> record : records) {
                    rawScanned++;

                    boolean resolved = dltResolutionRepository.existsByTopicAndPartitionNumberAndMessageOffset(
                            baseTopic, record.partition(), record.offset());
                    if (resolved) {
                        continue; // already handled - never shown, doesn't count toward paging
                    }

                    unresolvedSeen++;
                    if (unresolvedSeen > skip) {
                        long earliestOffset = beginningOffsets.get(new TopicPartition(dltTopic, record.partition()));
                        boolean deletable = record.offset() == earliestOffset;
                        messages.add(new DltMessageDTO(record.partition(), record.offset(), record.key(), record.value(), deletable));
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
        if (isAlreadyResolved(baseTopic, partition, offset)) {
            log.warn("Ignoring retry - message already resolved: topic={}, partition={}, offset={}", baseTopic, partition, offset);
            return;
        }

        String dltTopic = baseTopic + DLT_SUFFIX;
        ConsumerRecord<String, String> target = fetchRecord(dltTopic, partition, offset);
        if (target == null) {
            throw new NoSuchElementException("No message found at " + dltTopic + "[" + partition + "]@" + offset);
        }

        kafkaTemplate.send(baseTopic, target.key(), target.value());
        log.info("Replayed dead-lettered message: dltTopic={}, partition={}, offset={}, republishedTo={}",
                dltTopic, partition, offset, baseTopic);

        recordResolution(baseTopic, partition, offset, target.key(), target.value(), DltResolutionType.RETRIED);
        attemptImmediateDelete(dltTopic, partition, offset);
    }

    // Discards a dead-lettered message WITHOUT retrying it - for messages that should never be
    // reprocessed (a genuinely bad/obsolete payload, a payment that was cancelled some other way,
    // etc). Unlike the old version of this method, this always "succeeds" from the caller's point
    // of view: the message is marked resolved and hidden from the list immediately either way. If
    // it isn't the oldest record on its partition yet, the actual Kafka delete just waits for
    // DltCleanupScheduler instead of happening right now - that's expected, not an error.
    public void deleteMessage(String baseTopic, int partition, long offset) {
        if (isAlreadyResolved(baseTopic, partition, offset)) {
            log.warn("Ignoring delete - message already resolved: topic={}, partition={}, offset={}", baseTopic, partition, offset);
            return;
        }

        String dltTopic = baseTopic + DLT_SUFFIX;
        recordResolution(baseTopic, partition, offset, null, null, DltResolutionType.DELETED);
        boolean deletedNow = attemptImmediateDelete(dltTopic, partition, offset);
        log.info("Marked dead-lettered message as resolved (deleted): dltTopic={}, partition={}, offset={}, purgedFromKafkaNow={}",
                dltTopic, partition, offset, deletedNow);
    }

    // Called by DltCleanupScheduler. Starting from the current oldest record on the partition,
    // walks forward while each consecutive offset has a resolution recorded, then deletes that
    // whole resolved run from Kafka in a single call. Stops at the first unresolved offset it
    // finds, since Kafka can only trim a contiguous prefix - it can never skip over something that
    // still needs attention.
    void sweepPartition(String baseTopic, String dltTopic, TopicPartition topicPartition, long startOffset, long endOffsetExclusive) {
        long offset = startOffset;
        long lastResolvedOffset = startOffset - 1;
        int scanned = 0;

        while (offset < endOffsetExclusive && scanned < MAX_RECORDS_SCANNED) {
            if (!dltResolutionRepository.existsByTopicAndPartitionNumberAndMessageOffset(baseTopic, topicPartition.partition(), offset)) {
                break;
            }
            lastResolvedOffset = offset;
            offset++;
            scanned++;
        }

        if (lastResolvedOffset < startOffset) {
            return; // the oldest record isn't resolved yet - nothing to purge
        }

        try {
            performPhysicalDelete(dltTopic, topicPartition.partition(), lastResolvedOffset);
            log.info("Swept resolved DLT records: dltTopic={}, partition={}, deletedUpToOffset={}",
                    dltTopic, topicPartition.partition(), lastResolvedOffset);
        } catch (Exception e) {
            log.error("Failed to sweep resolved DLT records: dltTopic={}, partition={}, upToOffset={}",
                    dltTopic, topicPartition.partition(), lastResolvedOffset, e);
        }
    }

    List<TopicPartition> partitionsFor(String dltTopic, KafkaConsumer<String, String> consumer) {
        List<PartitionInfo> partitionInfos = consumer.partitionsFor(dltTopic);
        if (partitionInfos == null) {
            return List.of();
        }
        return partitionInfos.stream().map(info -> new TopicPartition(dltTopic, info.partition())).toList();
    }

    KafkaConsumer<String, String> newStandaloneConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
    }

    private boolean isAlreadyResolved(String baseTopic, int partition, long offset) {
        return dltResolutionRepository.existsByTopicAndPartitionNumberAndMessageOffset(baseTopic, partition, offset);
    }

    private ConsumerRecord<String, String> fetchRecord(String dltTopic, int partition, long offset) {
        TopicPartition topicPartition = new TopicPartition(dltTopic, partition);
        try (KafkaConsumer<String, String> consumer = newStandaloneConsumer()) {
            consumer.assign(List.of(topicPartition));
            consumer.seek(topicPartition, offset);

            ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
            for (ConsumerRecord<String, String> record : records) {
                if (record.partition() == partition && record.offset() == offset) {
                    return record;
                }
            }
        }
        return null;
    }

    private void recordResolution(String baseTopic, int partition, long offset, String key, String value, DltResolutionType type) {
        DltResolution resolution = new DltResolution();
        resolution.setTopic(baseTopic);
        resolution.setPartitionNumber(partition);
        resolution.setMessageOffset(offset);
        resolution.setResolutionType(type);
        resolution.setMessageKey(key);
        resolution.setMessageValue(value);
        dltResolutionRepository.save(resolution);
    }

    // Best-effort: only actually removes the record from Kafka if it's currently the oldest one on
    // its partition (the only case where deleting it doesn't also wipe out earlier, unresolved
    // messages). Never throws - if it can't delete right now, DltCleanupScheduler will handle it
    // once whatever comes before it is resolved too.
    private boolean attemptImmediateDelete(String dltTopic, int partition, long offset) {
        TopicPartition topicPartition = new TopicPartition(dltTopic, partition);
        try (KafkaConsumer<String, String> consumer = newStandaloneConsumer()) {
            long earliestOffset = consumer.beginningOffsets(List.of(topicPartition)).get(topicPartition);
            if (offset != earliestOffset) {
                return false; // something earlier on this partition still needs attention first
            }
        } catch (Exception e) {
            log.warn("Could not check whether offset {} on {} is deletable yet", offset, dltTopic, e);
            return false;
        }

        try {
            performPhysicalDelete(dltTopic, partition, offset);
            return true;
        } catch (Exception e) {
            log.warn("Could not immediately purge offset {} on {} from Kafka - will be cleaned up by the scheduled sweep instead",
                    offset, dltTopic, e);
            return false;
        }
    }

    // Kafka has no "delete this one record" operation - a partition is an append-only log, and
    // AdminClient.deleteRecords() can only trim everything BEFORE a given offset (move the low
    // watermark forward). Callers are responsible for only calling this when they've confirmed
    // offset is the oldest surviving record (attemptImmediateDelete) or the tail of an already-
    // verified consecutive resolved run starting at the oldest record (sweepPartition) - otherwise
    // this would silently delete unresolved messages too.
    void performPhysicalDelete(String dltTopic, int partition, long offset) throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        try (Admin admin = Admin.create(props)) {
            TopicPartition topicPartition = new TopicPartition(dltTopic, partition);
            admin.deleteRecords(Map.of(topicPartition, RecordsToDelete.beforeOffset(offset + 1)))
                    .all().get(10, TimeUnit.SECONDS);
        }
    }
}
