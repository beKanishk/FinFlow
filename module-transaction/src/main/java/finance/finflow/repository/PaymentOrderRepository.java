package finance.finflow.repository;

import finance.finflow.module.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    Optional<PaymentOrder> findByPaymentOrderId(UUID paymentOrderId);

    // Used by ReconciliationService. Without FOR UPDATE SKIP LOCKED, two app instances running the
    // same reconciliation sweep at the same time could both pick up the same SUCCESS order and both
    // decide its transaction is missing, each creating its own duplicate pending transaction for it
    // (there's no unique constraint on transactions.payment_order_id to catch that after the fact).
    // Locking here means each instance's sweep grabs a disjoint set of orders - same pattern already
    // used by OutboxEventRepository.findAndLockPendingBatch for the same reason.
    @Query(value = """
            SELECT * FROM payment_orders
            WHERE status = 'SUCCESS' AND updated_at < :cutoff
            ORDER BY updated_at ASC
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PaymentOrder> findAndLockDueForReconciliation(@Param("cutoff") Instant cutoff);
}
