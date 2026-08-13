package finance.finflow.service;

import finance.finflow.module.OutboxEvent;
import finance.finflow.module.OutboxEventStatus;
import finance.finflow.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final int MAX_RETRIES = 5;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    // Row locks from findAndLockPendingBatch only hold for the life of the surrounding
    // transaction, so the fetch and the publish-and-status-update loop below have to share one
    // transaction — otherwise the locks would be released the instant the SELECT returns,
    // defeating the point of taking them with SKIP LOCKED in the first place.
    @Transactional
    @Scheduled(fixedDelay = 1000)
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.findAndLockPendingBatch(Instant.now());
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            event.setStatus(OutboxEventStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            outboxEventRepository.save(event);

            log.info("Outbox event published: id={}, topic={}, aggregateId={}", event.getId(), event.getTopic(), event.getAggregateId());
        } catch (Exception e) {
            event.setRetryCount(event.getRetryCount() + 1);
            if (event.getRetryCount() >= MAX_RETRIES) {
                event.setStatus(OutboxEventStatus.FAILED);

                log.error("Outbox event permanently failed after {} retries: id={}, topic={}", event.getRetryCount(), event.getId(), event.getTopic(), e);
            } else {
                event.setNextRetryAt(calculateNextRetry(event.getRetryCount()));

                log.warn("Outbox event publish failed (retry {}/{}): id={}, topic={}, nextRetryAt={}",
                        event.getRetryCount(), MAX_RETRIES, event.getId(), event.getTopic(), event.getNextRetryAt(), e);
            }
            outboxEventRepository.save(event);
        }
    }


    private Instant calculateNextRetry(int retryCount) {
        long delaySeconds = (long) Math.pow(2, retryCount);
        return Instant.now().plusSeconds(delaySeconds);
    }
}
