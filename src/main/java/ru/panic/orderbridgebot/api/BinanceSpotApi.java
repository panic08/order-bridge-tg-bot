package ru.panic.orderbridgebot.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import ru.panic.orderbridgebot.api.payload.BinanceSpotGetTickerPriceResponse;

import java.util.List;

@Component
@RequiredArgsConstructor
public class BinanceSpotApi {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String BINANCE_SPOT_API_URL = "https://api.binance.com";

    public List<BinanceSpotGetTickerPriceResponse> getCryptosPrice() {
        ResponseEntity<String> cryptosResponseEntity =
                restTemplate.getForEntity(BINANCE_SPOT_API_URL + "/api/v3/ticker/price?symbols=[\"BTCUSDT\",\"LTCUSDT\"]",
                        String.class);

        try {
            return objectMapper.readValue(cryptosResponseEntity.getBody(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
