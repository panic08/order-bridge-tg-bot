package ru.panic.orderbridgebot.scheduler;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.panic.orderbridgebot.api.CoinGeckoApi;
import ru.panic.orderbridgebot.api.payload.CoinGeckoGetSimplePriceResponse;
import ru.panic.orderbridgebot.payload.type.CryptoToken;

import java.util.HashMap;
import java.util.Map;

@Component
@Data
@RequiredArgsConstructor
public class CryptoCurrency {
    private final CoinGeckoApi coinGeckoApi;

    private Map<CryptoToken, Double> usdPrice = new HashMap<>();

    @Scheduled(fixedDelay = 30000)
    public void updateCryptoCurrency() {
        CoinGeckoGetSimplePriceResponse getCryptosPrice = coinGeckoApi.getCryptosPrice();

        usdPrice.put(CryptoToken.BTC, getCryptosPrice.getBtc().getUsd());
        usdPrice.put(CryptoToken.LTC, getCryptosPrice.getLtc().getUsd());
        usdPrice.put(CryptoToken.USDT, getCryptosPrice.getUsdt().getUsd());
    }
}
