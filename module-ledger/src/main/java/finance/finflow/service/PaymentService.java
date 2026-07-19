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
import org.springframework.kafka.core.KafkaTemplate;
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
    private final KafkaTemplate<String, String> kafkaTemplate;
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

        if (order.getStatus() == PaymentStatus.FAILED) {
            publishPaymentFailedEvent(order, gatewayPaymentId);
        } else {
            publishPaymentCompletedEvent(order);
        }

        return new PaymentWebhookAckResponse(order.getPaymentOrderId(), order.getPaymentReference(), order.getStatus());
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

    private void publishPaymentCompletedEvent(PaymentOrder order) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                order.getPaymentOrderId(),
                order.getWallet().getWalletId(),
                order.getAmount(),
                order.getPaymentReference(),
                order.getPaymentMethod()
        );
        try {
            kafkaTemplate.send(PAYMENT_COMPLETED_TOPIC, event.getPaymentOrderId().toString(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        log.info("Payment completed event published: paymentOrderId={}, walletId={}, amount={}",
                event.getPaymentOrderId(), event.getWalletId(), event.getAmount());
    }

    private void publishPaymentFailedEvent(PaymentOrder order, String gatewayPaymentId) {
        PaymentFailedEvent event = new PaymentFailedEvent(
                order.getPaymentOrderId(),
                order.getWallet().getWalletId(),
                order.getAmount(),
                order.getPaymentReference(),
                order.getPaymentMethod(),
                gatewayPaymentId
        );
        try {
            kafkaTemplate.send(PAYMENT_FAILED_TOPIC, event.getPaymentOrderId().toString(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        log.info("Payment failed event published: paymentOrderId={}, walletId={}, amount={}",
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
