package finance.finflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import finance.finflow.dto.PaymentCompletedEvent;
import finance.finflow.dto.PaymentFailedEvent;
import finance.finflow.dto.PaymentInitiateResponse;
import finance.finflow.dto.PaymentWebhookAckResponse;
import finance.finflow.module.*;
import finance.finflow.repository.PaymentOrderRepository;
import finance.finflow.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final String PAYMENT_COMPLETED_TOPIC = "payment-completed";
    private static final String PAYMENT_FAILED_TOPIC = "payment-failed";

    private final PaymentOrderRepository paymentOrderRepository;
    private final WalletRepository walletRepository;
    private final IdempotencyService idempotencyService;
    private final OutboxEventService outboxEventService;
    private final TransactionStatusService transactionStatusService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Transactional
    public PaymentInitiateResponse initiate(UUID walletId, BigDecimal amount, PaymentMethod paymentMethod, String idempotencyKey) {
        String requestHash = hash(walletId + ":" + amount + ":" + paymentMethod);
        Optional<PaymentInitiateResponse> existing = idempotencyService.check(idempotencyKey, requestHash, PaymentInitiateResponse.class);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet wallet = walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new NoSuchElementException("Wallet not found: " + walletId));

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active: " + walletId);
        }

        UUID paymentOrderId = UUID.randomUUID();
        String date      = LocalDate.now(IST).format(DATE_FORMAT);
        String suffix    = paymentOrderId.toString().replace("-", "").substring(0, 8).toUpperCase();
        String reference = "PAY-" + date + "-" + suffix;

        PaymentOrder order = new PaymentOrder();
        order.setPaymentOrderId(paymentOrderId);
        order.setPaymentReference(reference);
        order.setWallet(wallet);
        order.setAmount(amount);
        order.setPaymentMethod(paymentMethod);
        order.setStatus(PaymentStatus.PENDING);
        order = paymentOrderRepository.save(order);

        PaymentInitiateResponse response = new PaymentInitiateResponse(
                order.getPaymentOrderId(), order.getPaymentReference(), amount, paymentMethod);
        idempotencyService.save(idempotencyKey, requestHash, order.getPaymentOrderId(), response);
        return response;
    }

    @Transactional
    public PaymentWebhookAckResponse handleWebhook(UUID paymentOrderId, PaymentStatus status, String gatewayPaymentId) {
        PaymentOrder order = findPendingOrder(paymentOrderId);

        updatePaymentOrder(order, status, gatewayPaymentId);

        // Create the PENDING transaction now, before the outbox even attempts to publish, so it's
        // visible in transaction history even if the outbox event never gets consumed (e.g. Kafka
        // stays unreachable past the retry budget) — the listeners resolve this same row later
        // instead of creating a new one.
        if (order.getStatus() == PaymentStatus.FAILED) {
            Transaction transaction = createPendingTransaction(order, "Payment failed | Ref: " + order.getPaymentReference());
            publishPaymentFailedEvent(order, gatewayPaymentId, transaction.getTransactionId());
        } else {
            Transaction transaction = createPendingTransaction(order, "Payment | Ref: " + order.getPaymentReference());
            publishPaymentCompletedEvent(order, transaction.getTransactionId());
        }

        return new PaymentWebhookAckResponse(order.getPaymentOrderId(), order.getPaymentReference(), order.getStatus());
    }

    private Transaction createPendingTransaction(PaymentOrder order, String description) {
        Transaction transaction = new Transaction();
        transaction.setType(TransactionType.DEPOSIT);
        transaction.setDestinationWallet(order.getWallet());
        transaction.setAmount(order.getAmount());
        transaction.setCurrency(order.getWallet().getCurrency());
        transaction.setPaymentMethod(order.getPaymentMethod());
        transaction.setDescription(description);
        return transactionStatusService.savePending(transaction);
    }

    private PaymentOrder findPendingOrder(UUID paymentOrderId) {
        PaymentOrder order = paymentOrderRepository.findByPaymentOrderId(paymentOrderId)
                .orElseThrow(() -> new NoSuchElementException("Payment order not found: " + paymentOrderId));
        if (order.getStatus() != PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment order already processed: " + paymentOrderId);
        }
        return order;
    }

    private void updatePaymentOrder(PaymentOrder order, PaymentStatus status, String gatewayPaymentId) {
        order.setStatus(status);
        order.setGatewayPaymentId(gatewayPaymentId);
        paymentOrderRepository.save(order);
    }

    private void publishPaymentCompletedEvent(PaymentOrder order, UUID transactionId) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                order.getPaymentOrderId(),
                order.getWallet().getWalletId(),
                transactionId,
                order.getAmount(),
                order.getPaymentReference(),
                order.getPaymentMethod()
        );
        try {
            outboxEventService.save(event.getPaymentOrderId().toString(), "PAYMENT_COMPLETED",
                    PAYMENT_COMPLETED_TOPIC, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        log.info("Payment completed event queued: paymentOrderId={}, walletId={}, amount={}",
                event.getPaymentOrderId(), event.getWalletId(), event.getAmount());
    }

    private void publishPaymentFailedEvent(PaymentOrder order, String gatewayPaymentId, UUID transactionId) {
        PaymentFailedEvent event = new PaymentFailedEvent(
                order.getPaymentOrderId(),
                order.getWallet().getWalletId(),
                transactionId,
                order.getAmount(),
                order.getPaymentReference(),
                order.getPaymentMethod(),
                gatewayPaymentId
        );
        try {
            outboxEventService.save(event.getPaymentOrderId().toString(), "PAYMENT_FAILED",
                    PAYMENT_FAILED_TOPIC, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        log.info("Payment failed event queued: paymentOrderId={}, walletId={}, amount={}",
                event.getPaymentOrderId(), event.getWalletId(), event.getAmount());
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
