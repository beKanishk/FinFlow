package finance.finflow.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

// Catches messages that DeadLetterPublishingRecoverer (in KafkaConfig) sends to a "<topic>.DLT"
// topic after a listener keeps failing on them. For now this just logs so we can see when a
// message gets dead-lettered instead of it disappearing silently - later this could alert or
// store the failed message somewhere for someone to look at and replay.
@Slf4j
@Component
public class DeadLetterListener {

    @KafkaListener(topics = {"payment-completed.DLT", "payment-failed.DLT", "wallet-credited.DLT"},
            groupId = "finflow-dlt-group")
    public void onDeadLetter(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String originalTopic) {
        log.error("Dead-lettered message received: originalTopic={}, payload={}", originalTopic, message);
    }
}
