package finance.finflow.controller;

import com.moduleauthentication.authentication.service.AuthHelper;
import finance.finflow.dto.ApiResponse;
import finance.finflow.dto.BalanceResponseDTO;
import finance.finflow.dto.CreateWalletRequest;
import finance.finflow.dto.WalletResponseDTO;
import finance.finflow.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final AuthHelper authHelper;

    @PostMapping
    public ApiResponse<WalletResponseDTO> createWallet(@RequestHeader("Authorization") String authHeader,
                                                        @Valid @RequestBody CreateWalletRequest request) {
        String username = authHelper.extractUsername(authHeader);
        return ApiResponse.ok(walletService.createWallet(username, request.getCurrency()));
    }

    @GetMapping("/me")
    public ApiResponse<WalletResponseDTO> getMyWallet(@RequestHeader("Authorization") String authHeader) {
        String username = authHelper.extractUsername(authHeader);
        return ApiResponse.ok(walletService.getWalletForUser(username));
    }

    @GetMapping("/{id}")
    public ApiResponse<WalletResponseDTO> getWallet(@PathVariable UUID id) {
        return ApiResponse.ok(walletService.getWallet(id));
    }

    @GetMapping("/{id}/balance")
    public ApiResponse<BalanceResponseDTO> getBalance(@PathVariable UUID id) {
        WalletResponseDTO wallet = walletService.getWallet(id);
        return ApiResponse.ok(new BalanceResponseDTO(wallet.getWalletId(), wallet.getAmount(), wallet.getCurrency()));
    }

    @PutMapping("/{id}/freeze")
    public ApiResponse<WalletResponseDTO> freeze(@PathVariable UUID id) {
        return ApiResponse.ok(walletService.freeze(id));
    }

    @PutMapping("/{id}/unfreeze")
    public ApiResponse<WalletResponseDTO> unfreeze(@PathVariable UUID id) {
        return ApiResponse.ok(walletService.unfreeze(id));
    }
}
