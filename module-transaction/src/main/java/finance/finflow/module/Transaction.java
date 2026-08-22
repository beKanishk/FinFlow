package finance.finflow.module;

import com.modulejpaaudit.AbstractBaseEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "transactions")
public class Transaction extends AbstractBaseEntity {

    @UuidGenerator
    @Column(name = "transaction_id", unique = true, nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "transaction_reference", unique = true, nullable = false)
    private String transactionReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @ManyToOne
    @JoinColumn(name = "source_wallet_id")
    private Wallet sourceWallet;

    @ManyToOne
    @JoinColumn(name = "destination_wallet_id")
    private Wallet destinationWallet;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethod paymentMethod;

    // Only set when this transaction was created from a payment webhook (PaymentService).
    // Direct deposits/withdrawals/transfers have no payment order behind them, so this stays null.
    // Lets reconciliation look up "the transaction for this payment order" directly instead of
    // guessing from a description string.
    @Column(name = "payment_order_id")
    private UUID paymentOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code")
    private FailureCode failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;
}
