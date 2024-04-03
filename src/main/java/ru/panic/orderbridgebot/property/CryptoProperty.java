package ru.panic.orderbridgebot.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "crypto")
@Getter
@Setter
public class CryptoProperty {
    private String ltcAddress;
    private String btcAddress;
    private String trc20Address;
}
