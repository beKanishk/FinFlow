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
    // True only for the oldest remaining message on its partition - that's the only one
    // DltAdminService can safely delete without also wiping out earlier, undealt-with messages
    // (see the guard in DltAdminService.performDelete). The frontend uses this to decide whether
    // to show a Delete button for a given row.
    private boolean deletable;
}
