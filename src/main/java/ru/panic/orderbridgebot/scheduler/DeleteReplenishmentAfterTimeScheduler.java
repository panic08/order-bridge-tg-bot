package ru.panic.orderbridgebot.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.panic.orderbridgebot.bot.TelegramBot;
import ru.panic.orderbridgebot.model.event.DeleteReplenishmentAfterTimeEvent;
import ru.panic.orderbridgebot.repository.DeleteReplenishmentAfterTimeEventRepository;
import ru.panic.orderbridgebot.repository.ReplenishmentRepository;

@Component
@RequiredArgsConstructor
public class DeleteReplenishmentAfterTimeScheduler {
    private final DeleteReplenishmentAfterTimeEventRepository deleteReplenishmentAfterTimeEventRepository;
    private final ReplenishmentRepository replenishmentRepository;
    private final TelegramBot telegramBot;

    @Scheduled(fixedDelay = 5000)
    public void deleteReplenishmentAfterTime() throws TelegramApiException {
        //todo Make getting all the events in installments
        Iterable<DeleteReplenishmentAfterTimeEvent> deleteReplenishmentAfterTimeEvents =
                deleteReplenishmentAfterTimeEventRepository.findAll();

        for (DeleteReplenishmentAfterTimeEvent event : deleteReplenishmentAfterTimeEvents) {
            if (event.getDeletedAt() <= System.currentTimeMillis()) {
                telegramBot.execute(EditMessageText.builder()
                                .chatId(event.getTelegramChatId())
                                .messageId(event.getTelegramMessageId())
                                .text("\uD83D\uDD64 <b>Время для оплаты заказа истекло</b>\n\n"
                                + "Если вы все ещё хотите оплатить заказ, повторите попытку. Не забудьте нажать кнопку \"Оплатил\" после отправки транзакции")
                                .parseMode("html")
                                .parseMode("html")

                        .build());

                deleteReplenishmentAfterTimeEventRepository.deleteById(event.getId());
                replenishmentRepository.deleteById(event.getReplenishmentId());
            }
        }
    }
}
