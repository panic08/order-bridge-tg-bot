package ru.panic.orderbridgebot.bot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.panic.orderbridgebot.property.TelegramBotProperty;

import java.util.concurrent.ExecutorService;

@Component
@RequiredArgsConstructor
public class TelegramBot extends TelegramLongPollingBot {
    private final TelegramBotProperty telegramBotProperty;
    private final ExecutorService executorService;

    @Override
    public String getBotToken() {
        return telegramBotProperty.getApiKey();
    }

    @Override
    public String getBotUsername() {
        return "null";
    }

    @Override
    public void onUpdateReceived(Update update) {
        executorService.submit(() -> {

        });
    }
}
