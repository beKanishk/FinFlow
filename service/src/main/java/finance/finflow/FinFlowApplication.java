package finance.finflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication(scanBasePackages = {"finance.finflow", "com.moduleauthentication", "com.modulejpaaudit"})
@EnableJpaAuditing
public class FinFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinFlowApplication.class, args);
    }

}
