package finance.finflow.module;

import com.modulejpaaudit.AbstractBaseEntity;
import finance.finflow.module.Wallet;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "payment_orders")
public class PaymentOrder extends AbstractBaseEntity {

    @UuidGenerator
    @Column(name = "payment_order_id", unique = true, nullable = false, updatable = false)
    private UUID paymentOrderId;

    @Column(name = "payment_reference", unique = true, nullable = false, updatable = false)
    private String paymentReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "gateway_payment_id")
    private String gatewayPaymentId;
}
