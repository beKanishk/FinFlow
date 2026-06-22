package finance.finflow.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MoneyRequest {
    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal amount;

    private String description;
}
