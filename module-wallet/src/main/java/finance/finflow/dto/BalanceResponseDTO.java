package finance.finflow.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@AllArgsConstructor
public class BalanceResponseDTO {
    private UUID walletId;
    private BigDecimal amount;
    private String currency;
}
