package finance.finflow.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import finance.finflow.dto.PaymentCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-events", groupId = "finflow-payment-group")
    public void onPaymentCompleted(String message) {
        PaymentCompletedEvent event = null;
        try {
            event = objectMapper.readValue(message, PaymentCompletedEvent.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        log.info("Payment event received: paymentOrderId={}, walletId={}, amount={}, completedAt={}",
                event.getPaymentOrderId(), event.getWalletId(), event.getAmount(), event.getCompletedAt());
    }
}
