package finance.finflow.service;

import finance.finflow.dto.PagedResponse;
import finance.finflow.dto.SearchRequest;
import finance.finflow.dto.TransactionHistoryDTO;
import finance.finflow.dto.TransactionResponseDTO;
import finance.finflow.dto.TransferResponseDTO;
import finance.finflow.module.*;
import finance.finflow.module.PaymentMethod;
import finance.finflow.repository.LedgerEntryRepository;
import finance.finflow.repository.TransactionRepository;
import finance.finflow.repository.UserRepository;
import finance.finflow.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final String FIELD_STATUS = "status";
    private static final String FIELD_TYPE   = "type";
    private static final String FIELD_FROM   = "from";
    private static final String FIELD_TO     = "to";

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionStatusService transactionStatusService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public TransactionResponseDTO deposit(UUID walletId, BigDecimal amount, String description, String idempotencyKey) {
        return deposit(walletId, amount, description, idempotencyKey, null);
    }

    @Transactional
    public TransactionResponseDTO deposit(UUID walletId, BigDecimal amount, String description, String idempotencyKey, PaymentMethod paymentMethod) {
        String requestHash = hash(walletId, amount, description);
        Optional<TransactionResponseDTO> existing = idempotencyService.check(idempotencyKey, requestHash, TransactionResponseDTO.class);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet wallet = walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new RuntimeException("Wallet not found: " + walletId));

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active: " + walletId);
        }

        Transaction transaction = new Transaction();

        transaction.setType(TransactionType.DEPOSIT);
        transaction.setDestinationWallet(wallet);
        transaction.setAmount(amount);
        transaction.setCurrency(wallet.getCurrency());
        transaction.setDescription(description);
        transaction.setPaymentMethod(paymentMethod);
        transaction = transactionStatusService.savePending(transaction);

        try {
            BigDecimal balanceAfter = wallet.getAmount().add(amount);

            LedgerEntry ledgerEntry = new LedgerEntry();
            ledgerEntry.setTransaction(transaction);
            ledgerEntry.setWallet(wallet);
            ledgerEntry.setEntryType(LedgerEntryType.CREDIT);
            ledgerEntry.setAmount(amount);
            ledgerEntry.setBalanceAfter(balanceAfter);
            ledgerEntry.setDescription(description);
            ledgerEntryRepository.save(ledgerEntry);

            wallet.setAmount(balanceAfter);
            wallet = walletRepository.save(wallet);

            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction = transactionRepository.save(transaction);

            TransactionResponseDTO response = toDto(transaction, wallet);
            idempotencyService.save(idempotencyKey, requestHash, transaction.getTransactionId(), response);
            return response;
        } catch (Exception e) {
            transactionStatusService.markFailed(transaction);
            throw e;
        }
    }

    @Transactional
    public TransactionResponseDTO withdraw(UUID walletId, BigDecimal amount, String description, String idempotencyKey) {
        String requestHash = hash(walletId, amount, description);
        Optional<TransactionResponseDTO> existing = idempotencyService.check(idempotencyKey, requestHash, TransactionResponseDTO.class);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet wallet = walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new RuntimeException("Wallet not found: " + walletId));

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active: " + walletId);
        }

        if (wallet.getAmount().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance in wallet: " + walletId);
        }

        Transaction transaction = new Transaction();

        transaction.setType(TransactionType.WITHDRAWAL);
        transaction.setSourceWallet(wallet);
        transaction.setAmount(amount);
        transaction.setCurrency(wallet.getCurrency());
        transaction.setDescription(description);
        transaction = transactionStatusService.savePending(transaction);

        try {
            BigDecimal balanceAfter = wallet.getAmount().subtract(amount);

            LedgerEntry ledgerEntry = new LedgerEntry();
            ledgerEntry.setTransaction(transaction);
            ledgerEntry.setWallet(wallet);
            ledgerEntry.setEntryType(LedgerEntryType.DEBIT);
            ledgerEntry.setAmount(amount);
            ledgerEntry.setBalanceAfter(balanceAfter);
            ledgerEntry.setDescription(description);
            ledgerEntryRepository.save(ledgerEntry);

            wallet.setAmount(balanceAfter);
            wallet = walletRepository.save(wallet);

            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction = transactionRepository.save(transaction);

            TransactionResponseDTO response = toDto(transaction, wallet);
            idempotencyService.save(idempotencyKey, requestHash, transaction.getTransactionId(), response);
            return response;
        } catch (Exception e) {
            transactionStatusService.markFailed(transaction);
            throw e;
        }
    }

    @Transactional
    public TransferResponseDTO transfer(UUID sourceWalletId, String destinationUsername, BigDecimal amount,
                                         String description, String idempotencyKey) {
        User destinationUser = userRepository.findByUsername(destinationUsername)
                .orElseThrow(() -> new RuntimeException("User not found: " + destinationUsername));
        Wallet destinationWallet = walletRepository.findByUserId(destinationUser.getId())
                .orElseThrow(() -> new RuntimeException("Wallet not found for user: " + destinationUsername));
        UUID destinationWalletId = destinationWallet.getWalletId();

        if (sourceWalletId.equals(destinationWalletId)) {
            throw new IllegalArgumentException("Source and destination wallet must be different: " + sourceWalletId);
        }

        String requestHash = hash(sourceWalletId, destinationWalletId, amount, description);
        Optional<TransferResponseDTO> existing = idempotencyService.check(idempotencyKey, requestHash, TransferResponseDTO.class);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet sourceWallet = walletRepository.findByWalletId(sourceWalletId)
                .orElseThrow(() -> new RuntimeException("Wallet not found: " + sourceWalletId));

        if (sourceWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active: " + sourceWalletId);
        }
        if (destinationWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active: " + destinationWalletId);
        }
        if (!sourceWallet.getCurrency().equals(destinationWallet.getCurrency())) {
            throw new IllegalStateException("Currency mismatch between wallets: "
                    + sourceWalletId + " (" + sourceWallet.getCurrency() + ") -> "
                    + destinationWalletId + " (" + destinationWallet.getCurrency() + ")");
        }
        if (sourceWallet.getAmount().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance in wallet: " + sourceWalletId);
        }

        Transaction transaction = new Transaction();

        transaction.setType(TransactionType.TRANSFER);
        transaction.setSourceWallet(sourceWallet);
        transaction.setDestinationWallet(destinationWallet);
        transaction.setAmount(amount);
        transaction.setCurrency(sourceWallet.getCurrency());
        transaction.setDescription(description);
        transaction = transactionStatusService.savePending(transaction);

        try {
            BigDecimal sourceBalanceAfter = sourceWallet.getAmount().subtract(amount);
            BigDecimal destinationBalanceAfter = destinationWallet.getAmount().add(amount);

            LedgerEntry debitEntry = new LedgerEntry();
            debitEntry.setTransaction(transaction);
            debitEntry.setWallet(sourceWallet);
            debitEntry.setEntryType(LedgerEntryType.DEBIT);
            debitEntry.setAmount(amount);
            debitEntry.setBalanceAfter(sourceBalanceAfter);
            debitEntry.setDescription(description);
            ledgerEntryRepository.save(debitEntry);

            LedgerEntry creditEntry = new LedgerEntry();
            creditEntry.setTransaction(transaction);
            creditEntry.setWallet(destinationWallet);
            creditEntry.setEntryType(LedgerEntryType.CREDIT);
            creditEntry.setAmount(amount);
            creditEntry.setBalanceAfter(destinationBalanceAfter);
            creditEntry.setDescription(description);
            ledgerEntryRepository.save(creditEntry);

            sourceWallet.setAmount(sourceBalanceAfter);
            sourceWallet = walletRepository.save(sourceWallet);

            destinationWallet.setAmount(destinationBalanceAfter);
            destinationWallet = walletRepository.save(destinationWallet);

            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction = transactionRepository.save(transaction);

            TransferResponseDTO response = new TransferResponseDTO(
                    transaction.getTransactionId(),
                    transaction.getTransactionReference(),
                    transaction.getType(),
                    transaction.getStatus(),
                    sourceWallet.getWalletId(),
                    destinationWallet.getWalletId(),
                    transaction.getAmount(),
                    transaction.getCurrency(),
                    sourceWallet.getAmount(),
                    destinationWallet.getAmount(),
                    transaction.getDescription()
            );
            idempotencyService.save(idempotencyKey, requestHash, transaction.getTransactionId(), response);
            return response;
        } catch (Exception e) {
            transactionStatusService.markFailed(transaction);
            throw e;
        }
    }

    public PagedResponse<TransactionHistoryDTO> getWalletTransactions(UUID walletId, SearchRequest search) {
        walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new NoSuchElementException("Wallet not found: " + walletId));

        String status = search.getString(FIELD_STATUS);
        String type   = search.getString(FIELD_TYPE);

        Instant fromInstant = null;
        if (search.getString(FIELD_FROM) != null) {
            fromInstant = search.getLocalDate(FIELD_FROM).atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        Instant toInstant = null;
        if (search.getString(FIELD_TO) != null) {
            toInstant = search.getLocalDate(FIELD_TO).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        return PagedResponse.of(transactionRepository.findByWallet(
                walletId.toString(), status, type, fromInstant, toInstant, search.toPageRequest()
        ).map(this::toHistoryDto));
    }

    public PagedResponse<TransactionHistoryDTO> getUserTransactions(String username, SearchRequest search) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));

        String status = search.getString(FIELD_STATUS);
        String type   = search.getString(FIELD_TYPE);

        Instant fromInstant = null;
        if (search.getString(FIELD_FROM) != null) {
            fromInstant = search.getLocalDate(FIELD_FROM).atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        Instant toInstant = null;
        if (search.getString(FIELD_TO) != null) {
            toInstant = search.getLocalDate(FIELD_TO).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        return PagedResponse.of(transactionRepository.findByUser(
                user.getId(), status, type, fromInstant, toInstant, search.toPageRequest()
        ).map(this::toHistoryDto));
    }

    private TransactionHistoryDTO toHistoryDto(Transaction t) {
        Wallet src  = t.getSourceWallet();
        Wallet dest = t.getDestinationWallet();
        return new TransactionHistoryDTO(
                t.getTransactionId(),
                t.getTransactionReference(),
                t.getType(),
                t.getStatus(),
                src  != null ? src.getWalletId()            : null,
                src  != null ? src.getUser().getUsername()  : null,
                dest != null ? dest.getWalletId()           : null,
                dest != null ? dest.getUser().getUsername() : null,
                t.getAmount(),
                t.getCurrency(),
                t.getDescription(),
                t.getPaymentMethod(),
                t.getCreatedAt()
        );
    }

    private TransactionResponseDTO toDto(Transaction transaction, Wallet wallet) {
        return new TransactionResponseDTO(
                transaction.getTransactionId(),
                transaction.getTransactionReference(),
                transaction.getType(),
                transaction.getStatus(),
                wallet.getWalletId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                wallet.getAmount(),
                transaction.getDescription()
        );
    }

    private String hash(UUID walletId, BigDecimal amount, String description) {
        return hash(walletId + ":" + amount + ":" + (description == null ? "" : description));
    }

    private String hash(UUID sourceWalletId, UUID destinationWalletId, BigDecimal amount, String description) {
        return hash(sourceWalletId + ":" + destinationWalletId + ":" + amount + ":" + (description == null ? "" : description));
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
