package finance.finflow.service;

import finance.finflow.module.User;
import finance.finflow.module.Wallet;
import finance.finflow.module.WalletStatus;
import finance.finflow.repository.UserRepository;
import finance.finflow.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    public Wallet createWallet(String username, String currency) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        if (walletRepository.findByUserId(user.getId()).isPresent()) {
            throw new RuntimeException("User already has a wallet: " + username);
        }

        Wallet wallet = new Wallet();
        wallet.setUser(user);
        wallet.setCurrency(currency);
        wallet.setAmount(BigDecimal.ZERO);
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setFreeze(false);
        return walletRepository.save(wallet);
    }

    public Wallet getWallet(UUID walletId) {
        return walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new RuntimeException("Wallet not found: " + walletId));
    }

    public Wallet getWalletForUser(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        return walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new RuntimeException("Wallet not found for user: " + username));
    }

    public Wallet freeze(UUID walletId) {
        Wallet wallet = getWallet(walletId);
        wallet.setFreeze(true);
        wallet.setStatus(WalletStatus.FROZEN);
        return walletRepository.save(wallet);
    }

    public Wallet unfreeze(UUID walletId) {
        Wallet wallet = getWallet(walletId);
        wallet.setFreeze(false);
        wallet.setStatus(WalletStatus.ACTIVE);
        return walletRepository.save(wallet);
    }
}
