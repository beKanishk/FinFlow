package finance.finflow.repository;

import finance.finflow.module.Transaction;
import finance.finflow.module.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByTransactionId(UUID transactionId);

    Optional<TransactionStatusView> findProjectedByPaymentOrderId(UUID paymentOrderId);

    long countByStatus(TransactionStatus status);

    @Query(value = """
            SELECT DISTINCT t.* FROM transactions t
            LEFT JOIN wallets sw ON t.source_wallet_id = sw.id
            LEFT JOIN wallets dw ON t.destination_wallet_id = dw.id
            WHERE (sw.wallet_id = CAST(:walletId AS uuid) OR dw.wallet_id = CAST(:walletId AS uuid))
            AND (:status IS NULL OR t.status = :status)
            AND (:type IS NULL OR t.type = :type)
            AND (CAST(:fromDate AS timestamptz) IS NULL OR t.created_at >= CAST(:fromDate AS timestamptz))
            AND (CAST(:toDate AS timestamptz) IS NULL OR t.created_at <= CAST(:toDate AS timestamptz))
            ORDER BY t.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT t.id) FROM transactions t
            LEFT JOIN wallets sw ON t.source_wallet_id = sw.id
            LEFT JOIN wallets dw ON t.destination_wallet_id = dw.id
            WHERE (sw.wallet_id = CAST(:walletId AS uuid) OR dw.wallet_id = CAST(:walletId AS uuid))
            AND (:status IS NULL OR t.status = :status)
            AND (:type IS NULL OR t.type = :type)
            AND (CAST(:fromDate AS timestamptz) IS NULL OR t.created_at >= CAST(:fromDate AS timestamptz))
            AND (CAST(:toDate AS timestamptz) IS NULL OR t.created_at <= CAST(:toDate AS timestamptz))
            """,
            nativeQuery = true)
    Page<Transaction> findByWallet(@Param("walletId") String walletId,
                                   @Param("status") String status,
                                   @Param("type") String type,
                                   @Param("fromDate") Instant fromDate,
                                   @Param("toDate") Instant toDate,
                                   Pageable pageable);

    @Query(value = """
            SELECT DISTINCT t.* FROM transactions t
            LEFT JOIN wallets sw ON t.source_wallet_id = sw.id
            LEFT JOIN wallets dw ON t.destination_wallet_id = dw.id
            LEFT JOIN users su ON sw.user_id = su.id
            LEFT JOIN users du ON dw.user_id = du.id
            WHERE (su.id = :userId OR du.id = :userId)
            AND (:status IS NULL OR t.status = :status)
            AND (:type IS NULL OR t.type = :type)
            AND (CAST(:fromDate AS timestamptz) IS NULL OR t.created_at >= CAST(:fromDate AS timestamptz))
            AND (CAST(:toDate AS timestamptz) IS NULL OR t.created_at <= CAST(:toDate AS timestamptz))
            ORDER BY t.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT t.id) FROM transactions t
            LEFT JOIN wallets sw ON t.source_wallet_id = sw.id
            LEFT JOIN wallets dw ON t.destination_wallet_id = dw.id
            LEFT JOIN users su ON sw.user_id = su.id
            LEFT JOIN users du ON dw.user_id = du.id
            WHERE (su.id = :userId OR du.id = :userId)
            AND (:status IS NULL OR t.status = :status)
            AND (:type IS NULL OR t.type = :type)
            AND (CAST(:fromDate AS timestamptz) IS NULL OR t.created_at >= CAST(:fromDate AS timestamptz))
            AND (CAST(:toDate AS timestamptz) IS NULL OR t.created_at <= CAST(:toDate AS timestamptz))
            """,
            nativeQuery = true)
    Page<Transaction> findByUser(@Param("userId") Long userId,
                                 @Param("status") String status,
                                 @Param("type") String type,
                                 @Param("fromDate") Instant fromDate,
                                 @Param("toDate") Instant toDate,
                                 Pageable pageable);
}
