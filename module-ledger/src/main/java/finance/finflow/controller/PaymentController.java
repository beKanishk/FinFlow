package finance.finflow.controller;

import finance.finflow.dto.ApiResponse;
import finance.finflow.dto.PaymentInitiateRequest;
import finance.finflow.dto.PaymentInitiateResponse;
import finance.finflow.dto.PaymentWebhookAckResponse;
import finance.finflow.dto.PaymentWebhookRequest;
import finance.finflow.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public ApiResponse<PaymentInitiateResponse> initiate(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                          @Valid @RequestBody PaymentInitiateRequest request) {
        return ApiResponse.ok(paymentService.initiate(request.getWalletId(), request.getAmount(), request.getPaymentMethod(), idempotencyKey));
    }

    @PostMapping("/webhook")
    public ApiResponse<PaymentWebhookAckResponse> webhook(@Valid @RequestBody PaymentWebhookRequest request) {
        return ApiResponse.ok(paymentService.handleWebhook(request.getPaymentOrderId(), request.getStatus(), request.getGatewayPaymentId()));
    }
}
