package finance.finflow.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DltMessageDTO {
    private int partition;
    private long offset;
    private String key;
    private String value;
}
