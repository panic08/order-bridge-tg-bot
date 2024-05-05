package ru.panic.orderbridgebot.api.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BinanceSpotGetTickerPriceResponse {
    private String symbol;
    private String price;
}
