package finance.finflow.dto;

import finance.finflow.module.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFailedEvent {

    private UUID paymentOrderId;
    private UUID walletId;
    private UUID transactionId;
    private BigDecimal amount;
    private String paymentReference;
    private PaymentMethod paymentMethod;
    private String gatewayPaymentId;
}
