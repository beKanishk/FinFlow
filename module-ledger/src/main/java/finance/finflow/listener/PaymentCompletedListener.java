package finance.finflow.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import finance.finflow.dto.PaymentCompletedEvent;
import finance.finflow.dto.TransactionResponseDTO;
import finance.finflow.dto.WalletCreditedEvent;
import finance.finflow.service.OutboxEventService;
import finance.finflow.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedListener {

    private static final String WALLET_CREDITED_TOPIC = "wallet-credited";

    private final TransactionService transactionService;
    private final OutboxEventService outboxEventService;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(topics = "payment-completed", groupId = "finflow-deposit-group")
    public void onPaymentCompleted(String message) {
        PaymentCompletedEvent event;
        try {
            event = objectMapper.readValue(message, PaymentCompletedEvent.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        TransactionResponseDTO result = transactionService.depositExisting(
                event.getTransactionId(),
                event.getWalletId(),
                event.getAmount(),
                "Payment | Ref: " + event.getPaymentReference(),
                event.getPaymentOrderId().toString()
        );

        publishWalletCreditedEvent(event, result);
    }

    private void publishWalletCreditedEvent(PaymentCompletedEvent source, TransactionResponseDTO result) {
        WalletCreditedEvent event = new WalletCreditedEvent(
                source.getPaymentOrderId(),
                source.getWalletId(),
                result.getTransactionId(),
                source.getAmount(),
                Instant.now()
        );
        try {
            outboxEventService.save(event.getPaymentOrderId().toString(), "WALLET_CREDITED",
                    WALLET_CREDITED_TOPIC, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        log.info("Wallet credited event queued: paymentOrderId={}, walletId={}, transactionId={}, amount={}",
                event.getPaymentOrderId(), event.getWalletId(), event.getTransactionId(), event.getAmount());
    }
}
