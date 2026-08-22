package finance.finflow.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import finance.finflow.dto.PaymentFailedEvent;
import finance.finflow.module.FailureCode;
import finance.finflow.service.TransactionStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFailedListener {

    private final TransactionStatusService transactionStatusService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-failed", groupId = "finflow-failure-group")
    public void onPaymentFailed(String message) {
        PaymentFailedEvent event;
        try {
            event = objectMapper.readValue(message, PaymentFailedEvent.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        // The PENDING transaction was already created in PaymentService before this event was
        // published — this just resolves it to FAILED, it doesn't create a new one.
        transactionStatusService.markFailedById(event.getTransactionId(), FailureCode.PAYMENT_GATEWAY_DECLINED,
                "Payment declined by gateway | gatewayPaymentId=" + event.getGatewayPaymentId());

        log.info("Payment failed event processed: paymentOrderId={}, walletId={}, transactionId={}, amount={}",
                event.getPaymentOrderId(), event.getWalletId(), event.getTransactionId(), event.getAmount());
    }
}
