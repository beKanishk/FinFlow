package finance.finflow.dto;

import finance.finflow.module.PaymentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class PaymentWebhookRequest {

    @NotNull
    private UUID paymentOrderId;

    @NotNull
    private PaymentStatus status;

    private String gatewayPaymentId;
}
