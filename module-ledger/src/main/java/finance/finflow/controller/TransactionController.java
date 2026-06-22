package finance.finflow.controller;

import finance.finflow.dto.MoneyRequest;
import finance.finflow.dto.TransactionResponseDTO;
import finance.finflow.dto.TransferRequest;
import finance.finflow.dto.TransferResponseDTO;
import finance.finflow.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/wallets/{id}/deposit")
    public TransactionResponseDTO deposit(@PathVariable UUID id,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @Valid @RequestBody MoneyRequest request) {
        return transactionService.deposit(id, request.getAmount(), request.getDescription(), idempotencyKey);
    }

    @PostMapping("/wallets/{id}/withdraw")
    public TransactionResponseDTO withdraw(@PathVariable UUID id,
                                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                                            @Valid @RequestBody MoneyRequest request) {
        return transactionService.withdraw(id, request.getAmount(), request.getDescription(), idempotencyKey);
    }

    @PostMapping("/wallets/{id}/transfer")
    public TransferResponseDTO transfer(@PathVariable UUID id,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @Valid @RequestBody TransferRequest request) {
        return transactionService.transfer(id, request.getDestinationUsername(), request.getAmount(),
                request.getDescription(), idempotencyKey);
    }
}
