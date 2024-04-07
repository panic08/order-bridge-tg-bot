package ru.panic.orderbridgebot.bot.pojo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GivePrefixObject {
    private int step;
    private int beginMessageId;
    private long telegramUserId;
    private long prefixId;
}
