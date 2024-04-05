package ru.panic.orderbridgebot.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "telegram.bots")
@Getter
@Setter
public class TelegramBotProperty {
    private String apiKey;

    private String channelUrl;

    private String botUrl;

    private Long channelChatId;

    private Long chatReplenishmentNotificationChatId;
    private Long chatWithdrawalNotificationChatId;
}
