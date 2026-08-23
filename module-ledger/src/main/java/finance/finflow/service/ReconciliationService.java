package finance.finflow.service;

import finance.finflow.module.OutboxEvent;
import finance.finflow.module.OutboxEventStatus;
import finance.finflow.module.PaymentOrder;
import finance.finflow.module.PaymentStatus;
import finance.finflow.module.Transaction;
import finance.finflow.module.TransactionStatus;
import finance.finflow.repository.OutboxEventRepository;
import finance.finflow.repository.PaymentOrderRepository;
import finance.finflow.repository.TransactionRepository;
import finance.finflow.repository.TransactionStatusView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

// Safety net that runs on its own slow schedule, well after the normal webhook -> outbox -> Kafka
// -> deposit path should have finished. It looks for money that a payment SAYS succeeded but that
// never actually landed in the wallet, and tries to get it moving again instead of leaving it stuck.
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private static final String PAYMENT_COMPLETED_EVENT_TYPE = "PAYMENT_COMPLETED";
    // Payment orders newer than this are left alone - the normal happy path (webhook -> outbox ->
    // OutboxPublisher's 1s poll -> Kafka -> consumer) can easily still be in flight at this point,
    // and we don't want reconciliation racing it.
    private static final long SAFETY_BUFFER_MINUTES = 2;

    private final PaymentOrderRepository paymentOrderRepository;
    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentService paymentService;


    @Transactional
    @Scheduled(fixedDelay = 300000)
    public void reconcile() {
        Instant cutoff = Instant.now().minus(SAFETY_BUFFER_MINUTES, ChronoUnit.MINUTES);
        List<PaymentOrder> orders = paymentOrderRepository.findByStatusAndUpdatedAtBefore(PaymentStatus.SUCCESS, cutoff);

        for (PaymentOrder order : orders) {
            reconcileOrder(order);
        }

        long failedCount = transactionRepository.countByStatus(TransactionStatus.FAILED);
        log.info("Reconciliation run complete: checkedOrders={}, currentFailedTransactions={}", orders.size(), failedCount);
    }


    private void reconcileOrder(PaymentOrder order) {
        Optional<TransactionStatusView> transactionOpt = transactionRepository.findProjectedByPaymentOrderId(order.getPaymentOrderId());

        if (transactionOpt.isEmpty()) {
            // The pending transaction should always exist by the time the order is SUCCESS
            // (PaymentService creates it in the same webhook call) - if it doesn't, something went
            // wrong before that write ever committed. Build it now so the deposit can still happen.
            log.warn("Reconciliation: SUCCESS payment order {} has no transaction at all, creating one now", order.getPaymentOrderId());
            Transaction transaction = paymentService.createPendingTransaction(order, "Payment | Ref: " + order.getPaymentReference());
            paymentService.publishPaymentCompletedEvent(order, transaction.getTransactionId());
            return;
        }

        TransactionStatusView transaction = transactionOpt.get();
        if (transaction.getStatus() != TransactionStatus.PENDING) {
            // COMPLETED -> already done, nothing to do.
            // FAILED/REVERSED -> a real validation failure (e.g. wallet went inactive) that needs a
            // human to look at, not something to silently retry.
            return;
        }

        Optional<OutboxEvent> outboxEventOpt = outboxEventRepository
                .findTopByAggregateIdAndEventTypeOrderByIdDesc(order.getPaymentOrderId().toString(), PAYMENT_COMPLETED_EVENT_TYPE);

        if (outboxEventOpt.isEmpty()) {
            log.warn("Reconciliation: PENDING transaction {} has no outbox event behind it, creating a recovery event",
                    transaction.getTransactionId());
            paymentService.publishPaymentCompletedEvent(order, transaction.getTransactionId());
            return;
        }

        OutboxEvent outboxEvent = outboxEventOpt.get();
        switch (outboxEvent.getStatus()) {
            case PENDING -> {
                // Still being retried by OutboxPublisher on its own schedule - leave it alone.
            }
            case FAILED -> {
                log.warn("Reconciliation: outbox event {} exhausted its retries, resetting it to PENDING to try again",
                        outboxEvent.getId());
                outboxEvent.setStatus(OutboxEventStatus.PENDING);
                outboxEvent.setRetryCount(0);
                outboxEvent.setNextRetryAt(Instant.now());
                outboxEventRepository.save(outboxEvent);
            }
            case PUBLISHED -> {
                // It reached Kafka fine, so the problem is downstream - most likely the consumer
                // itself kept failing and the message got dead-lettered. Resending the exact same
                // message wouldn't fix a consumer bug.
//                log.warn("Reconciliation: outbox event {} was published but transaction {} is still PENDING, creating a recovery event",
//                        outboxEvent.getId(), transaction.getTransactionId());
//                paymentService.publishPaymentCompletedEvent(order, transaction.getTransactionId());
            }
        }
    }
}
