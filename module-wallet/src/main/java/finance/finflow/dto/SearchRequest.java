package finance.finflow.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public class SearchRequest {

    private final Map<String, Object> data = new LinkedHashMap<>();

    @JsonAnySetter
    public void set(String key, Object value) {
        data.put(key, value);
    }

    public String getString(String key) {
        Object val = data.get(key);
        return val != null ? val.toString() : null;
    }

    public LocalDate getLocalDate(String key) {
        String val = getString(key);
        return val != null ? LocalDate.parse(val) : null;
    }

    public int getInt(String key, int defaultValue) {
        Object val = data.get(key);
        if (val == null) return defaultValue;
        return val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(val.toString());
    }

    public PageRequest toPageRequest() {
        return PageRequest.of(getInt("page", 0), getInt("size", 20));
    }
}
