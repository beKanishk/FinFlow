package finance.finflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import finance.finflow.dto.TransactionResponseDTO;
import finance.finflow.dto.TransferResponseDTO;
import finance.finflow.module.*;
import finance.finflow.repository.IdempotencyRecordRepository;
import finance.finflow.repository.LedgerEntryRepository;
import finance.finflow.repository.TransactionRepository;
import finance.finflow.repository.UserRepository;
import finance.finflow.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public TransactionResponseDTO deposit(UUID walletId, BigDecimal amount, String description, String idempotencyKey) {
        String requestHash = hash(walletId, amount, description);
        Optional<TransactionResponseDTO> existing = checkIdempotency(idempotencyKey, requestHash, TransactionResponseDTO.class);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet wallet = walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new RuntimeException("Wallet not found: " + walletId));

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active: " + walletId);
        }

        Transaction transaction = new Transaction();
        transaction.setTransactionReference(UUID.randomUUID().toString());
        transaction.setType(TransactionType.DEPOSIT);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setDestinationWallet(wallet);
        transaction.setAmount(amount);
        transaction.setCurrency(wallet.getCurrency());
        transaction.setDescription(description);
        transaction = transactionRepository.save(transaction);

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
        saveIdempotencyRecord(idempotencyKey, requestHash, transaction.getTransactionId(), response);
        return response;
    }

    @Transactional
    public TransactionResponseDTO withdraw(UUID walletId, BigDecimal amount, String description, String idempotencyKey) {
        String requestHash = hash(walletId, amount, description);
        Optional<TransactionResponseDTO> existing = checkIdempotency(idempotencyKey, requestHash, TransactionResponseDTO.class);
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
        transaction.setTransactionReference(UUID.randomUUID().toString());
        transaction.setType(TransactionType.WITHDRAWAL);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setSourceWallet(wallet);
        transaction.setAmount(amount);
        transaction.setCurrency(wallet.getCurrency());
        transaction.setDescription(description);
        transaction = transactionRepository.save(transaction);

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
        saveIdempotencyRecord(idempotencyKey, requestHash, transaction.getTransactionId(), response);
        return response;
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
        Optional<TransferResponseDTO> existing = checkIdempotency(idempotencyKey, requestHash, TransferResponseDTO.class);
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
        transaction.setTransactionReference(UUID.randomUUID().toString());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setSourceWallet(sourceWallet);
        transaction.setDestinationWallet(destinationWallet);
        transaction.setAmount(amount);
        transaction.setCurrency(sourceWallet.getCurrency());
        transaction.setDescription(description);
        transaction = transactionRepository.save(transaction);

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
        saveIdempotencyRecord(idempotencyKey, requestHash, transaction.getTransactionId(), response);
        return response;
    }

    private <T> Optional<T> checkIdempotency(String key, String requestHash, Class<T> responseType) {
        return idempotencyRecordRepository.findByIdempotencyKey(key)
                .map(existing -> {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        throw new IllegalStateException("Idempotency key reused with a different request: " + key);
                    }
                    return readResponse(existing.getResponse(), responseType);
                });
    }

    @SneakyThrows
    private void saveIdempotencyRecord(String key, String requestHash, UUID transactionId, Object response) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(key);
        record.setRequestHash(requestHash);
        record.setTransactionId(transactionId);
        record.setResponse(objectMapper.writeValueAsString(response));
        idempotencyRecordRepository.save(record);
    }

    @SneakyThrows
    private <T> T readResponse(String json, Class<T> type) {
        return objectMapper.readValue(json, type);
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
