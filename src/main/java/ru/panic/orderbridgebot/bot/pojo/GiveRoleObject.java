package ru.panic.orderbridgebot.bot.pojo;

import lombok.Builder;
import lombok.Data;
import ru.panic.orderbridgebot.model.type.UserRole;

@Data
@Builder
public class GiveRoleObject {
    private int step;
    private int beginMessageId;
    private long telegramUserId;
    private UserRole userRole;
}
