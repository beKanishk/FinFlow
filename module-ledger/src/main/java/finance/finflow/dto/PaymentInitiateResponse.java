package finance.finflow.dto;

import finance.finflow.module.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@AllArgsConstructor
public class PaymentInitiateResponse {
    private UUID paymentOrderId;
    private String paymentReference;
    private BigDecimal amount;
    private PaymentMethod paymentMethod;
}
