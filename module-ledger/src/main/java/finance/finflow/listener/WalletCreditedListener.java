package finance.finflow.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import finance.finflow.dto.WalletCreditedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WalletCreditedListener {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "wallet-credited", groupId = "finflow-notify-group")
    public void onWalletCredited(String message) {
        WalletCreditedEvent event;
        try {
            event = objectMapper.readValue(message, WalletCreditedEvent.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        log.info("Wallet credited event received: paymentOrderId={}, walletId={}, transactionId={}, amount={}, creditedAt={}",
                event.getPaymentOrderId(), event.getWalletId(), event.getTransactionId(), event.getAmount(), event.getCreditedAt());
    }
}
