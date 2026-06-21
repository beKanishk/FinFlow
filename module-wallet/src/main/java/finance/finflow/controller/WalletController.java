package finance.finflow.controller;

import com.moduleauthentication.authentication.service.AuthHelper;
import finance.finflow.dto.BalanceResponseDTO;
import finance.finflow.dto.CreateWalletRequest;
import finance.finflow.dto.WalletResponseDTO;
import finance.finflow.module.Wallet;
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
    public WalletResponseDTO createWallet(@RequestHeader("Authorization") String authHeader,
                                           @Valid @RequestBody CreateWalletRequest request) {
        String username = authHelper.extractUsername(authHeader);
        Wallet wallet = walletService.createWallet(username, request.getCurrency());
        return toDto(wallet);
    }

    @GetMapping("/me")
    public WalletResponseDTO getMyWallet(@RequestHeader("Authorization") String authHeader) {
        String username = authHelper.extractUsername(authHeader);
        return toDto(walletService.getWalletForUser(username));
    }

    @GetMapping("/{id}")
    public WalletResponseDTO getWallet(@PathVariable UUID id) {
        return toDto(walletService.getWallet(id));
    }

    @GetMapping("/{id}/balance")
    public BalanceResponseDTO getBalance(@PathVariable UUID id) {
        Wallet wallet = walletService.getWallet(id);
        return new BalanceResponseDTO(wallet.getWalletId(), wallet.getAmount(), wallet.getCurrency());
    }

    @PutMapping("/{id}/freeze")
    public WalletResponseDTO freeze(@PathVariable UUID id) {
        return toDto(walletService.freeze(id));
    }

    @PutMapping("/{id}/unfreeze")
    public WalletResponseDTO unfreeze(@PathVariable UUID id) {
        return toDto(walletService.unfreeze(id));
    }

    private WalletResponseDTO toDto(Wallet wallet) {
        return new WalletResponseDTO(
                wallet.getWalletId(),
                wallet.getUser().getUsername(),
                wallet.getAmount(),
                wallet.getCurrency(),
                wallet.getStatus(),
                wallet.isFreeze()
        );
    }
}
