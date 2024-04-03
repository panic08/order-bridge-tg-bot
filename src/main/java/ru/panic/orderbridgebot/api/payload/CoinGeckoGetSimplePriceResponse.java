package ru.panic.orderbridgebot.api.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CoinGeckoGetSimplePriceResponse {
    @JsonProperty("bitcoin")
    private CryptoCurrency btc;


    @JsonProperty("litecoin")
    private CryptoCurrency ltc;

    @JsonProperty("tether")
    private CryptoCurrency usdt;

    @Getter
    @Setter
    public static class CryptoCurrency {
        private double usd;
    }
}
