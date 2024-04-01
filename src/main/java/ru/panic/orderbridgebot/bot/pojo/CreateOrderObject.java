package ru.panic.orderbridgebot.bot.pojo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CreateOrderObject {
    private int step;
    private int beginMessageId;
    private String title;
    private String description;
    private List<String> prefixes;
}
