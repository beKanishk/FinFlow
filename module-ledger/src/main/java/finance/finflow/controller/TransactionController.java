package finance.finflow.controller;

import com.moduleauthentication.authentication.service.AuthHelper;
import finance.finflow.dto.ApiResponse;
import finance.finflow.dto.MoneyRequest;
import finance.finflow.dto.PagedResponse;
import finance.finflow.dto.SearchRequest;
import finance.finflow.dto.TransactionHistoryDTO;
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
    private final AuthHelper authHelper;

    @PostMapping("/wallets/{id}/deposit")
    public ApiResponse<TransactionResponseDTO> deposit(@PathVariable UUID id,
                                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                        @Valid @RequestBody MoneyRequest request) {
        return ApiResponse.ok(transactionService.deposit(id, request.getAmount(), request.getDescription(), idempotencyKey));
    }

    @PostMapping("/wallets/{id}/withdraw")
    public ApiResponse<TransactionResponseDTO> withdraw(@PathVariable UUID id,
                                                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                         @Valid @RequestBody MoneyRequest request) {
        return ApiResponse.ok(transactionService.withdraw(id, request.getAmount(), request.getDescription(), idempotencyKey));
    }

    @PostMapping("/wallets/{id}/transfer")
    public ApiResponse<TransferResponseDTO> transfer(@PathVariable UUID id,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @Valid @RequestBody TransferRequest request) {
        return ApiResponse.ok(transactionService.transfer(id, request.getDestinationUsername(), request.getAmount(),
                request.getDescription(), idempotencyKey));
    }

    @PostMapping("/wallets/{id}/transactions/search")
    public ApiResponse<PagedResponse<TransactionHistoryDTO>> getWalletTransactions(
            @PathVariable UUID id,
            @RequestBody SearchRequest search) {
        return ApiResponse.ok(transactionService.getWalletTransactions(id, search));
    }

    @PostMapping("/users/me/transactions/search")
    public ApiResponse<PagedResponse<TransactionHistoryDTO>> getUserTransactions(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody SearchRequest search) {
        String username = authHelper.extractUsername(authHeader);
        return ApiResponse.ok(transactionService.getUserTransactions(username, search));
    }
}
