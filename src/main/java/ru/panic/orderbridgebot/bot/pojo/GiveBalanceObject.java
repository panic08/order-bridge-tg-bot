package ru.panic.orderbridgebot.bot.pojo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GiveBalanceObject {
    private int step;
    private int beginMessageId;
    private long telegramUserId;
}
