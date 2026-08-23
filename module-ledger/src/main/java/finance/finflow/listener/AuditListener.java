package finance.finflow.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import finance.finflow.dto.PaymentCompletedEvent;
import finance.finflow.dto.PaymentFailedEvent;
import finance.finflow.dto.WalletCreditedEvent;
import finance.finflow.module.AuditLog;
import finance.finflow.repository.AuditLogRepository;
import finance.finflow.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Own consumer group ("finflow-audit-group"), separate from the business consumers - Kafka gives
// every consumer group its own full copy of a topic, so this listener sees every event regardless
// of whether the business consumer for the same message succeeds, fails, or gets dead-lettered.
// Covers both the normal topics and their .DLT counterparts, so dead-lettered events get an audit
// row too, not just successfully-processed ones.
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditListener {

    private final ObjectMapper objectMapper;
    private final WalletRepository walletRepository;
    private final AuditLogRepository auditLogRepository;

    @KafkaListener(topics = {
            "payment-completed", "payment-failed", "wallet-credited",
            "payment-completed.DLT", "payment-failed.DLT", "wallet-credited.DLT"
    }, groupId = "finflow-audit-group")
    public void onEvent(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        // A dead-lettered message has the exact same payload shape as the original - only the
        // topic name gained a ".DLT" suffix - so stripping it lets both parse the same way.
        String baseTopic = topic.endsWith(".DLT") ? topic.substring(0, topic.length() - 4) : topic;

        UUID walletId;
        String eventType;
        String details;
        try {
            switch (baseTopic) {
                case "payment-completed" -> {
                    PaymentCompletedEvent event = objectMapper.readValue(message, PaymentCompletedEvent.class);
                    walletId = event.getWalletId();
                    eventType = "PAYMENT_COMPLETED";
                    details = "Payment completed: amount=%s, ref=%s, method=%s".formatted(
                            event.getAmount(), event.getPaymentReference(), event.getPaymentMethod());
                }
                case "payment-failed" -> {
                    PaymentFailedEvent event = objectMapper.readValue(message, PaymentFailedEvent.class);
                    walletId = event.getWalletId();
                    eventType = "PAYMENT_FAILED";
                    details = "Payment failed: amount=%s, ref=%s, gatewayPaymentId=%s".formatted(
                            event.getAmount(), event.getPaymentReference(), event.getGatewayPaymentId());
                }
                case "wallet-credited" -> {
                    WalletCreditedEvent event = objectMapper.readValue(message, WalletCreditedEvent.class);
                    walletId = event.getWalletId();
                    eventType = "WALLET_CREDITED";
                    details = "Wallet credited: amount=%s, transactionId=%s".formatted(
                            event.getAmount(), event.getTransactionId());
                }
                default -> {
                    log.warn("Audit listener received a message from an unrecognized topic: {}", topic);
                    return;
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Audit listener could not parse message from topic {}: {}", topic, message, e);
            return;
        }

        // Wallet could theoretically be gone or reassigned by the time this runs - don't fail the
        // audit write just because the lookup didn't find anything.
        String username = walletRepository.findByWalletId(walletId)
                .map(wallet -> wallet.getUser().getUsername())
                .orElse(null);

        AuditLog entry = new AuditLog();
        entry.setTopic(topic);
        entry.setEventType(eventType);
        entry.setUserId(username);
        entry.setDetails(details);
        entry.setPayload(message);
        auditLogRepository.save(entry);

        log.info("Audit log recorded: topic={}, eventType={}, userId={}", topic, eventType, username);
    }
}
