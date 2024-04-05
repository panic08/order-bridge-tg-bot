package ru.panic.orderbridgebot.bot.pojo;

import lombok.Builder;
import lombok.Data;
import ru.panic.orderbridgebot.model.type.PaymentMethod;

@Data
@Builder
public class CreateWithdrawalObject {
    private int step;
    private int beginMessageId;
    private double amount;
    private PaymentMethod paymentMethod;
}
