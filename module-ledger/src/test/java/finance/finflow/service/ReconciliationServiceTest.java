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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

// These are plain Mockito unit tests (no Spring context) - fast, and enough to cover the branching
// logic in reconcileOrder() without needing a real database or Kafka. Each test builds only the
// mock state that one branch of the decision tree needs, then checks exactly what ReconciliationService
// did or (just as importantly) did NOT do - most of the risk here is doing something on a branch
// that should be a no-op, not the happy-path branches themselves.
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    private static final String PAYMENT_COMPLETED_EVENT_TYPE = "PAYMENT_COMPLETED";

    @Mock
    private PaymentOrderRepository paymentOrderRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private PaymentService paymentService;

    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        reconciliationService = new ReconciliationService(
                paymentOrderRepository, transactionRepository, outboxEventRepository, paymentService);
        // Called at the end of every reconcile() run just to log a count - stub it here once so
        // individual tests don't need to repeat it.
        when(transactionRepository.countByStatus(TransactionStatus.FAILED)).thenReturn(0L);
    }

    private PaymentOrder buildOrder() {
        PaymentOrder order = new PaymentOrder();
        order.setPaymentOrderId(UUID.randomUUID());
        order.setPaymentReference("PAY-20260101-ABCD1234");
        order.setAmount(new BigDecimal("100.00"));
        order.setStatus(PaymentStatus.SUCCESS);
        return order;
    }

    private void stubDueOrders(PaymentOrder... orders) {
        when(paymentOrderRepository.findByStatusAndUpdatedAtBefore(eq(PaymentStatus.SUCCESS), any(Instant.class)))
                .thenReturn(List.of(orders));
    }

    // getTransactionId() is only stubbed with lenient() because not every branch in
    // reconcileOrder() actually reads it once the transaction is PENDING (e.g. the FAILED/PENDING
    // outbox-status branches never touch it) - without lenient(), Mockito's strict stubbing would
    // fail those tests for an unused stub even though the mock setup itself is still correct.
    private TransactionStatusView pendingTransactionView(UUID transactionId) {
        TransactionStatusView view = mock(TransactionStatusView.class);
        when(view.getStatus()).thenReturn(TransactionStatus.PENDING);
        lenient().when(view.getTransactionId()).thenReturn(transactionId);
        return view;
    }

    @Test
    void noOrdersDue_doesNothing() {
        stubDueOrders();

        reconciliationService.reconcile();

        verify(transactionRepository, never()).findProjectedByPaymentOrderId(any());
        verifyNoMoreInteractions(paymentService, outboxEventRepository);
    }

    @Test
    void transactionMissingEntirely_createsRecoveryTransactionAndEvent() {
        PaymentOrder order = buildOrder();
        stubDueOrders(order);
        when(transactionRepository.findProjectedByPaymentOrderId(order.getPaymentOrderId())).thenReturn(Optional.empty());

        Transaction created = new Transaction();
        UUID newTransactionId = UUID.randomUUID();
        created.setTransactionId(newTransactionId);
        when(paymentService.createPendingTransaction(eq(order), anyString())).thenReturn(created);

        reconciliationService.reconcile();

        verify(paymentService).createPendingTransaction(order, "Payment | Ref: " + order.getPaymentReference());
        verify(paymentService).publishPaymentCompletedEvent(order, newTransactionId);
        verify(outboxEventRepository, never()).findTopByAggregateIdAndEventTypeOrderByIdDesc(any(), any());
    }

    @Test
    void transactionAlreadyCompleted_doesNothing() {
        PaymentOrder order = buildOrder();
        stubDueOrders(order);

        TransactionStatusView view = mock(TransactionStatusView.class);
        when(view.getStatus()).thenReturn(TransactionStatus.COMPLETED);
        when(transactionRepository.findProjectedByPaymentOrderId(order.getPaymentOrderId())).thenReturn(Optional.of(view));

        reconciliationService.reconcile();

        verifyNoMoreInteractions(paymentService, outboxEventRepository);
    }

    @Test
    void transactionAlreadyFailed_leftForAHumanNotRetried() {
        PaymentOrder order = buildOrder();
        stubDueOrders(order);

        TransactionStatusView view = mock(TransactionStatusView.class);
        when(view.getStatus()).thenReturn(TransactionStatus.FAILED);
        when(transactionRepository.findProjectedByPaymentOrderId(order.getPaymentOrderId())).thenReturn(Optional.of(view));

        reconciliationService.reconcile();

        verifyNoMoreInteractions(paymentService, outboxEventRepository);
    }

    @Test
    void pendingTransactionWithNoOutboxEvent_createsRecoveryEvent() {
        PaymentOrder order = buildOrder();
        stubDueOrders(order);

        UUID transactionId = UUID.randomUUID();
        TransactionStatusView view = pendingTransactionView(transactionId);
        when(transactionRepository.findProjectedByPaymentOrderId(order.getPaymentOrderId()))
                .thenReturn(Optional.of(view));
        when(outboxEventRepository.findTopByAggregateIdAndEventTypeOrderByIdDesc(
                order.getPaymentOrderId().toString(), PAYMENT_COMPLETED_EVENT_TYPE))
                .thenReturn(Optional.empty());

        reconciliationService.reconcile();

        verify(paymentService).publishPaymentCompletedEvent(order, transactionId);
        verify(paymentService, never()).createPendingTransaction(any(), any());
    }

    @Test
    void pendingOutboxEvent_leftAloneForOutboxPublisherToHandle() {
        PaymentOrder order = buildOrder();
        stubDueOrders(order);

        TransactionStatusView view = pendingTransactionView(UUID.randomUUID());
        when(transactionRepository.findProjectedByPaymentOrderId(order.getPaymentOrderId()))
                .thenReturn(Optional.of(view));

        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        event.setStatus(OutboxEventStatus.PENDING);
        when(outboxEventRepository.findTopByAggregateIdAndEventTypeOrderByIdDesc(
                order.getPaymentOrderId().toString(), PAYMENT_COMPLETED_EVENT_TYPE))
                .thenReturn(Optional.of(event));

        reconciliationService.reconcile();

        verify(paymentService, never()).publishPaymentCompletedEvent(any(), any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void failedOutboxEvent_isResetToPendingForAnotherAttempt() {
        PaymentOrder order = buildOrder();
        stubDueOrders(order);

        TransactionStatusView view = pendingTransactionView(UUID.randomUUID());
        when(transactionRepository.findProjectedByPaymentOrderId(order.getPaymentOrderId()))
                .thenReturn(Optional.of(view));

        OutboxEvent event = new OutboxEvent();
        event.setId(2L);
        event.setStatus(OutboxEventStatus.FAILED);
        event.setRetryCount(5);
        when(outboxEventRepository.findTopByAggregateIdAndEventTypeOrderByIdDesc(
                order.getPaymentOrderId().toString(), PAYMENT_COMPLETED_EVENT_TYPE))
                .thenReturn(Optional.of(event));

        reconciliationService.reconcile();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(saved.getRetryCount()).isZero();
        assertThat(saved.getNextRetryAt()).isNotNull();
        verify(paymentService, never()).publishPaymentCompletedEvent(any(), any());
    }

    @Test
    void publishedOutboxEventStillStuckPending_isCurrentlyANoOp() {
        // The PUBLISHED branch in ReconciliationService is commented out right now, so a message
        // that reached Kafka but whose consumer never completed it (e.g. it got dead-lettered)
        // isn't recovered automatically yet. This test documents that as the current, deliberate
        // behavior - if that branch gets re-enabled, update this test to expect a recovery event.
        PaymentOrder order = buildOrder();
        stubDueOrders(order);

        TransactionStatusView view = pendingTransactionView(UUID.randomUUID());
        when(transactionRepository.findProjectedByPaymentOrderId(order.getPaymentOrderId()))
                .thenReturn(Optional.of(view));

        OutboxEvent event = new OutboxEvent();
        event.setId(3L);
        event.setStatus(OutboxEventStatus.PUBLISHED);
        when(outboxEventRepository.findTopByAggregateIdAndEventTypeOrderByIdDesc(
                order.getPaymentOrderId().toString(), PAYMENT_COMPLETED_EVENT_TYPE))
                .thenReturn(Optional.of(event));

        reconciliationService.reconcile();

        verify(paymentService, never()).publishPaymentCompletedEvent(any(), any());
        verify(outboxEventRepository, never()).save(any());
    }
}
