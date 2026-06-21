package finance.finflow.dto;

import finance.finflow.module.WalletStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@AllArgsConstructor
public class WalletResponseDTO {
    private UUID walletId;
    private String username;
    private BigDecimal amount;
    private String currency;
    private WalletStatus status;
    private boolean isFreeze;
}
