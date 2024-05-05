package ru.panic.orderbridgebot.scheduler;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.panic.orderbridgebot.api.BinanceSpotApi;
import ru.panic.orderbridgebot.api.payload.BinanceSpotGetTickerPriceResponse;
import ru.panic.orderbridgebot.payload.type.CryptoToken;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Data
@RequiredArgsConstructor
public class CryptoCurrency {
    private final BinanceSpotApi binanceSpotApi;

    private Map<CryptoToken, Double> usdPrice = new HashMap<>();

    @Scheduled(fixedDelay = 30000)
    public void updateCryptoCurrency() {
        List<BinanceSpotGetTickerPriceResponse> binanceSpotGetTickerPriceResponse =
                binanceSpotApi.getCryptosPrice();

        for (BinanceSpotGetTickerPriceResponse crypto : binanceSpotGetTickerPriceResponse) {
            if (crypto.getSymbol().equals("BTCUSDT")) {
                usdPrice.put(CryptoToken.BTC, Double.valueOf(crypto.getPrice()));
            } else if (crypto.getSymbol().equals("LTCUSDT")) {
                usdPrice.put(CryptoToken.LTC, Double.valueOf(crypto.getPrice()));
            }
        }
        usdPrice.put(CryptoToken.USDT, 0.998);
    }
}
