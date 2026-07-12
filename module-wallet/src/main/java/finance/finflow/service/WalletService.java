package finance.finflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import finance.finflow.dto.WalletResponseDTO;
import finance.finflow.module.User;
import finance.finflow.module.Wallet;
import finance.finflow.module.WalletStatus;
import finance.finflow.repository.UserRepository;
import finance.finflow.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {

    private static final String PREFIX = "wallet:";
    private static final Duration TTL   = Duration.ofMinutes(30);

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public WalletResponseDTO createWallet(String username, String currency) {
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
        wallet = walletRepository.save(wallet);

        WalletResponseDTO dto = toDto(wallet);
        cacheWallet(dto);
        return dto;
    }

    public WalletResponseDTO getWallet(UUID walletId) {
        String cached = redisTemplate.opsForValue().get(PREFIX + walletId);
        if (cached != null) {
            return deserialize(cached);
        }

        Wallet wallet = walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new RuntimeException("Wallet not found: " + walletId));

        WalletResponseDTO dto = toDto(wallet);
        cacheWallet(dto);
        return dto;
    }

    public WalletResponseDTO getWalletForUser(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new RuntimeException("Wallet not found for user: " + username));

        // Delegate to getWallet so Redis cache is checked/populated by walletId
        return getWallet(wallet.getWalletId());
    }

    public WalletResponseDTO freeze(UUID walletId) {
        Wallet wallet = walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new RuntimeException("Wallet not found: " + walletId));
        wallet.setFreeze(true);
        wallet.setStatus(WalletStatus.FROZEN);
        wallet = walletRepository.save(wallet);

        WalletResponseDTO dto = toDto(wallet);
        cacheWallet(dto);
        return dto;
    }

    public WalletResponseDTO unfreeze(UUID walletId) {
        Wallet wallet = walletRepository.findByWalletId(walletId)
                .orElseThrow(() -> new RuntimeException("Wallet not found: " + walletId));
        wallet.setFreeze(false);
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet = walletRepository.save(wallet);

        WalletResponseDTO dto = toDto(wallet);
        cacheWallet(dto);
        return dto;
    }

    public void evict(UUID walletId) {
        redisTemplate.delete(PREFIX + walletId);
    }

    private void cacheWallet(WalletResponseDTO dto) {
        redisTemplate.opsForValue().set(PREFIX + dto.getWalletId(), serialize(dto), TTL);
    }

    public static WalletResponseDTO toDto(Wallet wallet) {
        return new WalletResponseDTO(
                wallet.getWalletId(),
                wallet.getUser().getUsername(),
                wallet.getAmount(),
                wallet.getCurrency(),
                wallet.getStatus(),
                wallet.isFreeze()
        );
    }

    @SneakyThrows
    private String serialize(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    @SneakyThrows
    private WalletResponseDTO deserialize(String json) {
        return objectMapper.readValue(json, WalletResponseDTO.class);
    }
}
