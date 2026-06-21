package finance.finflow.repository;

import finance.finflow.module.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByWalletId(UUID walletId);
    Optional<Wallet> findByUserId(Long userId);
}
