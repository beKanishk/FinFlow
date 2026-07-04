package finance.finflow.repository;

import finance.finflow.module.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    Optional<PaymentOrder> findByPaymentOrderId(UUID paymentOrderId);
}
