package finance.finflow.dto;

import finance.finflow.module.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentWebhookAckResponse {

    private UUID paymentOrderId;
    private String paymentReference;
    private PaymentStatus status;
}
