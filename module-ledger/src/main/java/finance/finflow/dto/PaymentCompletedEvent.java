package finance.finflow.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCompletedEvent {

    private UUID paymentOrderId;
    private UUID walletId;
    private BigDecimal amount;
    private Instant completedAt;
}
