package app.sverka;

import java.time.Clock;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SverkaApplication {
    public static void main(String[] args) {
        ZipSecureFile.setMaxEntrySize(24L * 1024 * 1024);
        ZipSecureFile.setMaxFileCount(350);
        SpringApplication.run(SverkaApplication.class, args);
    }

    @Bean Clock clock() {
        return Clock.systemUTC();
    }
}
