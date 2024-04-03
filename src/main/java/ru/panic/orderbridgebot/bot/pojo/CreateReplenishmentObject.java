package ru.panic.orderbridgebot.bot.pojo;

import lombok.Builder;
import lombok.Data;
import ru.panic.orderbridgebot.model.type.PaymentMethod;

@Data
@Builder
public class CreateReplenishmentObject {
    private int step;
    private int beginMessageId;
    private long orderId;
    private Double amount;
    private PaymentMethod paymentMethod;
}
