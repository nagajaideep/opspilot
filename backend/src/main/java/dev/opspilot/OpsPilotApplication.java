package dev.opspilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication          // = @Configuration + @EnableAutoConfiguration + @ComponentScan
@EnableScheduling               // enables @Scheduled (nightly policy job)
@ConfigurationPropertiesScan    // binds @ConfigurationProperties records from application.yml
public class OpsPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsPilotApplication.class, args);
    }
}
