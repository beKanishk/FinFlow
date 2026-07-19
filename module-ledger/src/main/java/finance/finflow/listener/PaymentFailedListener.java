package finance.finflow.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import finance.finflow.dto.PaymentFailedEvent;
import finance.finflow.module.Transaction;
import finance.finflow.module.TransactionType;
import finance.finflow.module.Wallet;
import finance.finflow.repository.WalletRepository;
import finance.finflow.service.TransactionStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFailedListener {

    private final WalletRepository walletRepository;
    private final TransactionStatusService transactionStatusService;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(topics = "payment-failed", groupId = "finflow-failure-group")
    public void onPaymentFailed(String message) {
        PaymentFailedEvent event;
        try {
            event = objectMapper.readValue(message, PaymentFailedEvent.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        Wallet wallet = walletRepository.findByWalletId(event.getWalletId())
                .orElseThrow(() -> new NoSuchElementException("Wallet not found: " + event.getWalletId()));

        Transaction failed = new Transaction();
        failed.setType(TransactionType.DEPOSIT);
        failed.setDestinationWallet(wallet);
        failed.setAmount(event.getAmount());
        failed.setCurrency(wallet.getCurrency());
        failed.setPaymentMethod(event.getPaymentMethod());
        failed.setDescription("Payment failed | Ref: " + event.getPaymentReference());
        failed = transactionStatusService.savePending(failed);
        transactionStatusService.markFailed(failed);

        log.info("Payment failed event processed: paymentOrderId={}, walletId={}, amount={}",
                event.getPaymentOrderId(), event.getWalletId(), event.getAmount());
    }
}
