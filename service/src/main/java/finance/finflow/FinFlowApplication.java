package finance.finflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"finance.finflow", "com.moduleauthentication", "com.modulejpaaudit"})
@EnableJpaAuditing
@EnableScheduling
public class FinFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinFlowApplication.class, args);
    }

}
