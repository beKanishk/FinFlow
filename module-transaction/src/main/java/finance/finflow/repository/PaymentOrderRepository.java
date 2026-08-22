package finance.finflow.repository;

import finance.finflow.module.PaymentOrder;
import finance.finflow.module.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    Optional<PaymentOrder> findByPaymentOrderId(UUID paymentOrderId);

    List<PaymentOrder> findByStatusAndUpdatedAtBefore(PaymentStatus status, Instant updatedAt);
}
