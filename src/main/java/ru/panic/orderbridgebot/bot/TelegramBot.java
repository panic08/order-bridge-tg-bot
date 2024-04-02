package ru.panic.orderbridgebot.bot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.panic.orderbridgebot.bot.callback.BackCallback;
import ru.panic.orderbridgebot.bot.callback.CustomerCallback;
import ru.panic.orderbridgebot.bot.callback.UserCallback;
import ru.panic.orderbridgebot.bot.pojo.CreateOrderObject;
import ru.panic.orderbridgebot.model.Order;
import ru.panic.orderbridgebot.model.Prefix;
import ru.panic.orderbridgebot.model.User;
import ru.panic.orderbridgebot.model.type.OrderStatus;
import ru.panic.orderbridgebot.model.type.UserRole;
import ru.panic.orderbridgebot.property.TelegramBotProperty;
import ru.panic.orderbridgebot.repository.OrderRepository;
import ru.panic.orderbridgebot.repository.PrefixRepository;
import ru.panic.orderbridgebot.repository.UserRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;

@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {
    private final TelegramBotProperty telegramBotProperty;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final PrefixRepository prefixRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final Map<Long, CreateOrderObject> createOrderSteps = new HashMap<>();

    public TelegramBot(TelegramBotProperty telegramBotProperty, UserRepository userRepository, OrderRepository orderRepository, PrefixRepository prefixRepository, ObjectMapper objectMapper, ExecutorService executorService) {
        this.telegramBotProperty = telegramBotProperty;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.prefixRepository = prefixRepository;
        this.objectMapper = objectMapper;
        this.executorService = executorService;

        List<BotCommand> listOfCommands = new ArrayList<>();

        listOfCommands.add(new BotCommand("/start", "\uD83D\uDD04 Перезапустить"));
        listOfCommands.add(new BotCommand("/profile", "\uD83D\uDC64 Профиль"));

        try {
            execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

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

            if (update.hasMessage() && update.getMessage().hasText()) {
                String text = update.getMessage().getText();
                long chatId = update.getMessage().getChatId();
                long principalTelegramId = update.getMessage().getFrom().getId();
                int messageId = update.getMessage().getMessageId();

                User principalUser = userRepository.findByTelegramId(principalTelegramId)
                        .orElseGet(() -> {
                            User newUser = User.builder()
                                    .telegramId(principalTelegramId)
                                    .balance(0d)
                                    .role(UserRole.CUSTOMER)
                                    .executorStatus(null)
                                    .executorPrefixes("[]")
                                    .registeredAt(System.currentTimeMillis())
                                    .build();

                            return userRepository.save(newUser);
                        });

                if (text.contains("/start") && text.split(" ").length > 1) {
                    handleTakeOrderMessage(chatId, messageId, principalUser, Long.parseLong(text.split(" ")[1]));
                    return;
                }

                switch (text) {
                    case "/start" -> {
                        handleStartMessage(chatId, principalUser);
                        return;
                    }

                    case "/profile", "\uD83D\uDC64 Профиль" -> {
                        handleProfileMessage(chatId, principalUser, update.getMessage().getFrom().getFirstName());
                        return;
                    }

                    case "➕ Создать заказ" -> {
                        handleCreateOrderMessage(chatId);
                        return;
                    }

                    case "\uD83D\uDCCB Мои заказы" -> {
                        handleCreateGetAllOrderMessage(chatId, principalUser, 1);
                        return;
                    }
                }

                if (createOrderSteps.get(principalUser.getId()) != null) {
                    handleCreateOrderProcess(chatId, messageId, principalUser, text);
                    return;
                }

                handleUnknownMessage(chatId, principalUser);

                return;
            } else if (update.hasCallbackQuery()) {
                long chatId = update.getCallbackQuery().getMessage().getChatId();
                int messageId = update.getCallbackQuery().getMessage().getMessageId();
                String callbackQueryId = update.getCallbackQuery().getId();
                String data = update.getCallbackQuery().getData();
                long principalTelegramId = update.getCallbackQuery().getFrom().getId();

                User principalUser = userRepository.findByTelegramId(principalTelegramId)
                        .orElseGet(() -> {
                            User newUser = User.builder()
                                    .telegramId(principalTelegramId)
                                    .balance(0d)
                                    .role(UserRole.CUSTOMER)
                                    .registeredAt(System.currentTimeMillis())
                                    .build();

                            return userRepository.save(newUser);
                        });

                switch (data) {
                    case "answerQuery" -> {
                        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                                .callbackQueryId(callbackQueryId)
                                .build());
                        return;
                    }
                    case CustomerCallback.CREATE_ORDER_CALLBACK -> {
                        createOrderSteps.put(principalUser.getId(), CreateOrderObject.builder()
                                .step(1)
                                .beginMessageId(messageId)
                                .prefixes(new ArrayList<>())
                                .build());

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton createOrderButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_TO_MAIN_CREATE_ORDER_CALLBACK)
                                .text("↩\uFE0F Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(createOrderButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        EditMessageText editMessageText = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .parseMode("html")
                                .text("\uD83D\uDCDD <b>Ввод названия заказа</b>\n\n"
                                + "Пожалуйста, введите краткое название вашего заказа. Это поможет исполнителям лучше понять, о чем идет речь\n\n"
                                + "<b>Пример:</b> <i>\"Создание логотипа\", \"Написание статьи\", \"Разработка веб-сайта\"</i>\n\n"
                                + "\uD83D\uDD0D <b>Ждем вашего заказа!</b>")
                                .build();

                        handleEditMessageText(editMessageText);
                        return;
                    }

                    case CustomerCallback.ACCEPT_PREFIX_CREATE_ORDER_CALLBACK -> {
                        CreateOrderObject createOrderObject = createOrderSteps.get(principalUser.getId());

                        createOrderObject.setStep(4);

                        createOrderSteps.put(principalUser.getId(), createOrderObject);

                        handleCreateOrderProcess(chatId, null, principalUser, null);
                        return;
                    }

                    case CustomerCallback.DONT_MIND_CREATE_ORDER_CALLBACK -> {
                        CreateOrderObject createOrderObject = createOrderSteps.get(principalUser.getId());

                        createOrderObject.setStep(4);
                        createOrderObject.setPrefixes(new ArrayList<>());

                        createOrderSteps.put(principalUser.getId(), createOrderObject);

                        handleCreateOrderProcess(chatId, null, principalUser, null);
                        return;
                    }

                    case CustomerCallback.DEAL_BUDGET_CREATE_ORDER_CALLBACK -> {
                        CreateOrderObject createOrderObject = createOrderSteps.get(principalUser.getId());

                        createOrderObject.setStep(5);

                        createOrderSteps.put(principalUser.getId(), createOrderObject);

                        handleCreateOrderProcess(chatId, null, principalUser, null);
                    }


                    case BackCallback.BACK_TO_MAIN_CREATE_ORDER_CALLBACK -> {
                        createOrderSteps.remove(principalUser.getId());

                        handleEditCreateOrderMessage(chatId, messageId);

                        return;
                    }

                    case BackCallback.BACK_TO_GET_ALL_ORDER_CALLBACK -> {
                        handleEditGetAllOrderMessage(chatId, messageId, principalUser, 1);
                        return;
                    }
                }

                if (data.contains(CustomerCallback.SELECT_PREFIX_CREATE_ORDER_CALLBACK)) {
                    long prefixId = Long.parseLong(data.split(" ")[5]);

                    Prefix prefix = prefixRepository.findById(prefixId).orElseThrow();

                    CreateOrderObject createOrderObject = createOrderSteps.get(principalUser.getId());

                    createOrderObject.setStep(2);

                    List<String> prefixes = createOrderObject.getPrefixes();

                    if (prefixes.contains(prefix.getName())) {
                        prefixes.remove(prefix.getName());
                    } else {
                        prefixes.add(prefix.getName());
                    }

                    createOrderObject.setPrefixes(prefixes);

                    createOrderSteps.put(principalUser.getId(), createOrderObject);

                    handleCreateOrderProcess(chatId, null, principalUser, null);

                    return;
                }

                if (data.contains(UserCallback.GET_CURRENT_ORDER_CALLBACK)) {
                    long orderId = Long.parseLong(data.split(" ")[4]);

                    handleEditGetCurrentOrderMessage(chatId, messageId, orderId, principalUser);
                    return;
                }

                if (data.contains(CustomerCallback.UP_ORDER_CALLBACK)) {
                    long orderId = Long.parseLong(data.split(" ")[3]);

                    handleUpOrder(chatId, callbackQueryId, orderId);
                    return;
                }

                if (data.contains(UserCallback.GET_ALL_ORDER_CALLBACK)) {
                    int page = Integer.parseInt(data.split(" ")[4]);

                    handleEditGetAllOrderMessage(chatId, messageId, principalUser, page);
                    return;
                }

            }

        });
    }

    private void handleUnknownMessage(long chatId, User principalUser) {
        SendMessage sendMessage = SendMessage.builder()
                .text("\uD83C\uDF10 <b>Такой команды не существует. Попробуйте что-нибудь из меню!</b>")
                .chatId(chatId)
                .replyMarkup(getDefaultReplyKeyboardMarkup(principalUser))
                .parseMode("html")
                .build();

        handleSendTextMessage(sendMessage);
    }

    private void handleStartMessage(long chatId, User principalUser) {
        SendMessage sendMessage = SendMessage.builder()
                .text("\uD83D\uDC4B Приветствую вас в <b>OrderBridge!</b>\n\n"
                + "\uD83E\uDD16 <b>Я — ваш персональный помощник по связи между исполнителями и заказчиками.</b>\n\n"
                + "\uD83C\uDFAF Моя цель - облегчить процесс поиска и выполнения заказов, чтобы ваш бизнес процветал.\n\n"
                + "\uD83D\uDCAC Не стесняйтесь обращаться ко мне, если у вас есть вопросы или вам нужна помощь.\n\n"
                + "\uD83D\uDEE0\uFE0F <b>Давайте вместе делать ваш бизнес лучше!</b>")
                .chatId(chatId)
                .replyMarkup(getDefaultReplyKeyboardMarkup(principalUser))
                .parseMode("html")
                .build();

        handleSendTextMessage(sendMessage);
    }

    private void handleProfileMessage(long chatId, User principalUser, String username) {
        // Конвертация миллисекунд в LocalDateTime
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(principalUser.getRegisteredAt()), ZoneId.systemDefault());

        // Форматирование даты и времени в требуемый формат
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yy в HH:mm");
        String formattedDateTime = dateTime.format(formatter);

        String principalUserRole = null;

        // Выбираем текстовую роль для юзера
        switch (principalUser.getRole()) {
            case CUSTOMER -> principalUserRole = "Заказчик";
            case EXECUTOR -> principalUserRole = "Исполнитель";
            case ADMIN -> principalUserRole = "Администратор";
        }

        long principalUserOrdersCount = orderRepository.countByCustomerUserIdOrExecutorUserId(principalUser.getId(),
                principalUser.getId());

        String executorStatusString = null;

        if (principalUser.getRole().equals(UserRole.EXECUTOR)) {
            executorStatusString = "ℹ\uFE0F <b>Статус исполнителя:</b> <code>" + principalUser.getExecutorStatus() + "</code>\n\n";
        } else {
            executorStatusString = "";
        }

        SendMessage message = SendMessage.builder()
                .text("\uD83D\uDC64 <b>Профиль</b>\n\n"
                        + "\uD83D\uDD11 <b>ID:</b> <code>" + principalUser.getTelegramId() + "</code>\n"
                        + "\uD83E\uDEAA <b>Имя:</b> <code>" + username + "</code>\n"
                        + "\uD83C\uDFAD <b>Роль:</b> <code>" + principalUserRole + "</code>\n"
                        + executorStatusString
                        + "\uD83D\uDCB3 <b>Баланс:</b> <code>" + principalUser.getBalance() + "$</code>\n\n"
                        + "\uD83D\uDECD <b>Количество открытых заказов:</b> <code>" + principalUserOrdersCount + "</code>\n\n"
                        + "\uD83D\uDD52 <b>Дата регистрации:</b> <code>" + formattedDateTime + "</code>")
                .chatId(chatId)
                .parseMode("html")
                .build();

        handleSendTextMessage(message);
    }

    private void handleCreateOrderMessage(long chatId) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton createOrderButton = InlineKeyboardButton.builder()
                .callbackData(CustomerCallback.CREATE_ORDER_CALLBACK)
                .text("➕ Создать заказ")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(createOrderButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage message = SendMessage.builder()
                .text("➕ <b>Создать заказ</b>\n\n"
                + "Чтобы создать заказ, вам нужно будет указать необходимую информацию. <b>Не забудьте уточнить все детали!</b>\n\n"
                + "\uD83D\uDD17 Ваши заказы публикуются автоматически по ссылке: " + telegramBotProperty.getChannelUrl() + "\n\n"
                + "\uD83D\uDEE0\uFE0F <b>Не беспокойтесь, мы позаботимся о вашем заказе!</b>\n\n"
                + "<b>\uD83E\uDEF0 Для быстрого доступа к созданию заказа, нажмите на кнопку ниже</b>")
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .disableWebPagePreview(true)
                .parseMode("html")
                .build();

        handleSendTextMessage(message);
    }

    private void handleEditCreateOrderMessage(long chatId, int messageId) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton createOrderButton = InlineKeyboardButton.builder()
                .callbackData(CustomerCallback.CREATE_ORDER_CALLBACK)
                .text("➕ Создать заказ")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(createOrderButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .text("➕ <b>Создать заказ</b>\n\n"
                        + "Чтобы создать заказ, вам нужно будет указать необходимую информацию. <b>Не забудьте уточнить все детали!</b>\n\n"
                        + "\uD83D\uDD17 Ваши заказы публикуются автоматически по ссылке: " + telegramBotProperty.getChannelUrl() + "\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Не беспокойтесь, мы позаботимся о вашем заказе!</b>\n\n"
                        + "<b>\uD83E\uDEF0 Для быстрого доступа к созданию заказа, нажмите на кнопку ниже</b>")
                .chatId(chatId)
                .messageId(messageId)
                .replyMarkup(inlineKeyboardMarkup)
                .disableWebPagePreview(true)
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleCreateOrderProcess(long chatId, Integer messageId, User principalUser, String text) {
        CreateOrderObject createOrderObject = createOrderSteps.get(principalUser.getId());

        switch (createOrderObject.getStep()) {
            case 1 -> {
                createOrderObject.setStep(2);
                createOrderObject.setTitle(text);

                createOrderSteps.put(principalUser.getId(), createOrderObject);

                if (messageId != null) {
                    handleDeleteMessage(DeleteMessage.builder()
                            .chatId(chatId)
                            .messageId(messageId)
                            .build());
                }

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_CREATE_ORDER_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                keyboardButtonsRow1.add(backButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);

                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(createOrderObject.getBeginMessageId())
                        .replyMarkup(inlineKeyboardMarkup)
                        .parseMode("html")
                        .text("✏\uFE0F <b>Ввод технического задания</b>\n\n"
                                + "Отлично! Теперь, пожалуйста, предоставьте более подробное техническое задание (ТЗ) для вашего заказа. Опишите все важные детали, требования и предпочтения\n\n"
                                + "\uD83D\uDCCB Например, укажите необходимые функции, дизайнерские предпочтения, сроки выполнения и любую другую важную информацию.\n\n"
                                + "\uD83D\uDCDD <b>Мы готовы к вашим подробностям!</b>")
                        .build();

                handleEditMessageText(editMessageText);
            }
            case 2 -> {
                createOrderObject.setStep(3);

                if (text != null) {
                    createOrderObject.setDescription(text);
                }

                createOrderSteps.put(principalUser.getId(), createOrderObject);

                if (messageId != null) {
                    handleDeleteMessage(DeleteMessage.builder()
                            .chatId(chatId)
                            .messageId(messageId)
                            .build());
                }

                Iterable<Prefix> prefixList = prefixRepository.findAll();

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                for (Prefix prefix : prefixList) {
                    String prefixName;

                    if (createOrderObject.getPrefixes().isEmpty()) {
                        prefixName = "❌ " + prefix.getName();
                    } else {
                        if (createOrderObject.getPrefixes().contains(prefix.getName())) {
                            prefixName = "✅ " + prefix.getName();
                        } else {
                            prefixName = "❌" + prefix.getName();
                        }
                    }


                    InlineKeyboardButton prefixButton = InlineKeyboardButton.builder()
                            .callbackData(CustomerCallback.SELECT_PREFIX_CREATE_ORDER_CALLBACK + " " + prefix.getId())
                            .text(prefixName)
                            .build();

                    List<InlineKeyboardButton> prefixKeyboardButtonsRow = new ArrayList<>();

                    prefixKeyboardButtonsRow.add(prefixButton);

                    rowList.add(prefixKeyboardButtonsRow);
                }

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();

                InlineKeyboardButton dontMindButton = InlineKeyboardButton.builder()
                        .callbackData(CustomerCallback.DONT_MIND_CREATE_ORDER_CALLBACK)
                        .text("Нет понимания")
                        .build();

                InlineKeyboardButton acceptButton = InlineKeyboardButton.builder()
                        .callbackData(CustomerCallback.ACCEPT_PREFIX_CREATE_ORDER_CALLBACK)
                        .text("Подтвердить")
                        .build();

                InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_CREATE_ORDER_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                keyboardButtonsRow1.add(dontMindButton);
                keyboardButtonsRow2.add(acceptButton);
                keyboardButtonsRow3.add(backButton);

                rowList.add(keyboardButtonsRow1);
                rowList.add(keyboardButtonsRow2);
                rowList.add(keyboardButtonsRow3);

                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(createOrderObject.getBeginMessageId())
                        .replyMarkup(inlineKeyboardMarkup)
                        .parseMode("html")
                        .text("\uD83C\uDFAF <b>Выбор префикса для заказа</b>\n\n"
                                + "Отлично! Теперь давайте выберем подходящий префикс для вашего заказа. Это поможет исполнителям лучше понять, с каким языком программирования или технологией связан ваш проект\n\n"
                                + "\uD83D\uDD0D <b>Пожалуйста,</b> выберите один из следующих префиксов\n\n"
                                + "\uD83D\uDEE0\uFE0F <b>Мы готовы перейти к следующему шагу!</b>")
                        .build();

                handleEditMessageText(editMessageText);
            }

            case 4 -> {
                createOrderObject.setStep(5);

                createOrderSteps.put(principalUser.getId(), createOrderObject);

                if (messageId != null) {
                    handleDeleteMessage(DeleteMessage.builder()
                            .chatId(chatId)
                            .messageId(messageId)
                            .build());
                }

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                InlineKeyboardButton dealBudgetButton = InlineKeyboardButton.builder()
                        .callbackData(CustomerCallback.DEAL_BUDGET_CREATE_ORDER_CALLBACK)
                        .text("Договорной")
                        .build();

                InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_CREATE_ORDER_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();

                keyboardButtonsRow1.add(dealBudgetButton);
                keyboardButtonsRow2.add(backButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);
                rowList.add(keyboardButtonsRow2);

                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(createOrderObject.getBeginMessageId())
                        .replyMarkup(inlineKeyboardMarkup)
                        .parseMode("html")
                        .text("\uD83D\uDCB0 <b>Ввод бюджета</b>\n\n"
                                + "Отлично! Теперь, пожалуйста, укажите свой бюджет на выполнение заказа. Это поможет исполнителям лучше оценить работу и предложить вам наилучшее решение\n\n"
                                + "\uD83D\uDCCA <b>Укажите ваш бюджет в формате числа (например, 500) и валюте USD ($)</b>\n\n"
                                + "\uD83D\uDCB8 <b>Мы готовы к вашему бюджету!</b>")
                        .build();

                handleEditMessageText(editMessageText);
            }

            case 5 -> {
                if (messageId != null) {
                    handleDeleteMessage(DeleteMessage.builder()
                            .chatId(chatId)
                            .messageId(messageId)
                            .build());
                }

                Double budget = null;

                if (text != null) {
                    budget = Double.parseDouble(text);
                }

                Order order = null;

                try {
                    order = Order.builder()
                            .orderStatus(OrderStatus.ACTIVE)
                            .customerUserId(principalUser.getId())
                            .title(createOrderObject.getTitle())
                            .description(createOrderObject.getDescription())
                            .budget(budget)
                            .prefixes(objectMapper.writeValueAsString(createOrderObject.getPrefixes()))
                            .lastUppedAt(System.currentTimeMillis())
                            .createdAt(System.currentTimeMillis())
                            .build();
                } catch (JsonProcessingException e) {
                    log.warn(e.getMessage());
                }

                assert order != null;
                orderRepository.save(order);

                order.setTelegramChannelMessageId(generateOrderTextAndSend(order.getId(), createOrderObject.getTitle(),
                        createOrderObject.getDescription(), budget, createOrderObject.getPrefixes()));

                orderRepository.save(order);

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_CREATE_ORDER_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                keyboardButtonsRow1.add(backButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);

                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(createOrderObject.getBeginMessageId())
                        .replyMarkup(inlineKeyboardMarkup)
                        .parseMode("html")
                        .text("\uD83D\uDE80 <b>Заказ успешно опубликован!</b>\n\n"
                                + "Поздравляем! Ваш заказ был успешно опубликован\n\n"
                                + "\uD83C\uDF89 <b>Теперь наши исполнители могут просматривать его и предлагать свои услуги</b>\n\n"
                                + "\uD83D\uDCBC Если у вас есть какие-либо дополнительные вопросы или требуется изменение заказа, не стесняйтесь обращаться к нам\n\n"
                                + "<b>Благодарим за использование нашего сервиса! Удачи в реализации вашего проекта!</b> \uD83C\uDF1F")
                        .build();

                handleEditMessageText(editMessageText);
            }
        }
    }

    private void handleCreateGetAllOrderMessage(long chatId, User principalUser, int page) {
        List<Order> principalOrders = orderRepository
                .findAllByCustomerUserIdOrExecutorUserIdWithOffsetOrderByCreatedAtDesc(principalUser.getId(),
                        principalUser.getId(),
                        6,
                        6 * (page - 1));

        long principalOrdersCount = orderRepository.countByCustomerUserIdOrExecutorUserId(principalUser.getId(),
                principalUser.getId());

        long maxPage = (long) Math.ceil((double) principalOrdersCount / 6);

        if (maxPage == 0) {
            maxPage = 1;
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        for (Order order : principalOrders) {
            String orderStatusString = null;

            switch (order.getOrderStatus()) {
                case ACTIVE -> orderStatusString = "Активный";
                case IN_PROGRESS -> orderStatusString = "В разработке";
                case COMPLETED -> orderStatusString = "Завершен";
            }

            List<InlineKeyboardButton> orderKeyboardButtonsRow = new ArrayList<>();

            InlineKeyboardButton orderButton = InlineKeyboardButton.builder()
                    .callbackData(UserCallback.GET_CURRENT_ORDER_CALLBACK + " " + order.getId())
                    .text(order.getTitle() + " / " + (order.getBudget() == null ? "Договорный" : order.getBudget() + "$")
                            + " / " + orderStatusString)
                    .build();

            orderKeyboardButtonsRow.add(orderButton);

            rowList.add(orderKeyboardButtonsRow);
        }

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        if (page != 1) {
            InlineKeyboardButton prevButton = InlineKeyboardButton.builder()
                    .callbackData(UserCallback.GET_ALL_ORDER_CALLBACK + " " + (page - 1))
                    .text("⏪ Предыдущая")
                    .build();

            keyboardButtonsRow1.add(prevButton);
        }

        InlineKeyboardButton currentButton = InlineKeyboardButton.builder()
                .callbackData("answerQuery")
                .text("Страница " + page + " / " + maxPage)
                .build();

        keyboardButtonsRow1.add(currentButton);

        if (page != maxPage) {
            InlineKeyboardButton nextButton = InlineKeyboardButton.builder()
                    .callbackData(UserCallback.GET_ALL_ORDER_CALLBACK + " " + (page + 1))
                    .text("⏩ Следующая")
                    .build();

            keyboardButtonsRow1.add(nextButton);
        }

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage sendMessage = SendMessage.builder()
                .text("\uD83D\uDCCB <b>Мои заказы</b>\n\n"
                + "<b>Добро пожаловать в раздел ваших заказов!</b> Здесь вы можете просматривать и управлять своими активными заказами\n\n"
                + "\uD83D\uDCC4 Для просмотра всех ваших заказов, выберите соответствующую страницу с помощью кнопок ниже\n\n"
                + "\uD83D\uDEE0\uFE0F <b>Если у вас есть какие-либо вопросы или вам нужна помощь, не стесняйтесь обращаться к нам</b>")
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleSendTextMessage(sendMessage);
    }

    private void handleEditGetAllOrderMessage(long chatId, int messageId, User principalUser, int page) {
        List<Order> principalOrders = orderRepository
                .findAllByCustomerUserIdOrExecutorUserIdWithOffsetOrderByCreatedAtDesc(principalUser.getId(),
                        principalUser.getId(),
                        6,
                        6 * (page - 1));

        long principalOrdersCount = orderRepository.countByCustomerUserIdOrExecutorUserId(principalUser.getId(),
                principalUser.getId());

        long maxPage = (long) Math.ceil((double) principalOrdersCount / 6);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        for (Order order : principalOrders) {
            String orderStatusString = null;

            switch (order.getOrderStatus()) {
                case ACTIVE -> orderStatusString = "Активный";
                case IN_PROGRESS -> orderStatusString = "В разработке";
                case COMPLETED -> orderStatusString = "Завершен";
            }

            List<InlineKeyboardButton> orderKeyboardButtonsRow = new ArrayList<>();

            InlineKeyboardButton orderButton = InlineKeyboardButton.builder()
                    .callbackData(UserCallback.GET_CURRENT_ORDER_CALLBACK + " " + order.getId())
                    .text(order.getTitle() + " / " + (order.getBudget() == null ? "Договорный" : order.getBudget() + "$")
                            + " / " + orderStatusString)
                    .build();

            orderKeyboardButtonsRow.add(orderButton);

            rowList.add(orderKeyboardButtonsRow);
        }

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        if (page != 1) {
            InlineKeyboardButton prevButton = InlineKeyboardButton.builder()
                    .callbackData(UserCallback.GET_ALL_ORDER_CALLBACK + " " + (page - 1))
                    .text("⏪ Предыдущая")
                    .build();

            keyboardButtonsRow1.add(prevButton);
        }

        InlineKeyboardButton currentButton = InlineKeyboardButton.builder()
                .callbackData("answerQuery")
                .text("Страница " + page + " / " + maxPage)
                .build();

        keyboardButtonsRow1.add(currentButton);

        if (page != maxPage) {
            InlineKeyboardButton nextButton = InlineKeyboardButton.builder()
                    .callbackData(UserCallback.GET_ALL_ORDER_CALLBACK + " " + (page + 1))
                    .text("⏩ Следующая")
                    .build();

            keyboardButtonsRow1.add(nextButton);
        }

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .text("\uD83D\uDCCB <b>Мои заказы</b>\n\n"
                        + "<b>Добро пожаловать в раздел ваших заказов!</b> Здесь вы можете просматривать и управлять своими активными заказами\n\n"
                        + "\uD83D\uDCC4 Для просмотра всех ваших заказов, выберите соответствующую страницу с помощью кнопок ниже\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Если у вас есть какие-либо вопросы или вам нужна помощь, не стесняйтесь обращаться к нам</b>")
                .chatId(chatId)
                .messageId(messageId)
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleEditGetCurrentOrderMessage(long chatId, int messageId, long orderId, User principalUser) {
        Order currentOrder = orderRepository.findById(orderId)
                .orElseThrow();

        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(currentOrder.getCreatedAt()), ZoneId.systemDefault());

        // Форматирование даты и времени в требуемый формат
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yy в HH:mm");
        String formattedCurrentCreateOrderDateTime = dateTime.format(formatter);

        String currentOrderStatusString = null;
        List<String> currentOrderPrefixes = null;

        try {
            currentOrderPrefixes = objectMapper.readValue(currentOrder.getPrefixes(), ArrayList.class);
        } catch (JsonProcessingException e) {
            log.warn(e.getMessage());
        }

        switch (currentOrder.getOrderStatus()) {
            case ACTIVE -> currentOrderStatusString = "Активный";
            case IN_PROGRESS -> currentOrderStatusString = "В разработке";
            case COMPLETED -> currentOrderStatusString = "Завершен";
        }

        StringBuilder getCurrentOrderTextMessage = new StringBuilder()
                .append("\uD83D\uDCDD <b>Информация о заказе</b>\n\n")
                .append("\uD83D\uDCCC <b>Статус:</b> <code>").append(currentOrderStatusString).append("</code>\n");

        if (!currentOrderPrefixes.isEmpty()) {
            getCurrentOrderTextMessage.append("\uD83D\uDD16 <b>Префиксы:</b> <code>");

            for (int i = 0; i < currentOrderPrefixes.size(); i++) {
                if (i == currentOrderPrefixes.size() - 1) {
                    getCurrentOrderTextMessage.append(currentOrderPrefixes.get(i));
                } else {
                    getCurrentOrderTextMessage.append(currentOrderPrefixes.get(i)).append(", ");
                }
            }

            getCurrentOrderTextMessage.append("</code>\n");
        }

        getCurrentOrderTextMessage.append("\uD83D\uDCCB <b>Заголовок:</b> ").append(currentOrder.getTitle()).append("\n")
                .append("\uD83D\uDCDD <b>Описание:</b>\n\n<i>").append(currentOrder.getDescription()).append("</i>\n\n")
                .append("\uD83D\uDCB0 <b>Бюджет:</b> <code>").append((currentOrder.getBudget() == null ? "Договорный" : currentOrder.getBudget() + "$")).append("</code>\n")
                .append("\uD83D\uDCC5 <b>Дата создания:</b> <code>").append(formattedCurrentCreateOrderDateTime).append("</code>\n");

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();

        if (currentOrder.getCustomerUserId().equals(principalUser.getId())) {
            InlineKeyboardButton upOrderButton = InlineKeyboardButton.builder()
                    .callbackData(CustomerCallback.UP_ORDER_CALLBACK + " " + orderId)
                    .text("\uD83C\uDD99 Поднять заказ")
                    .build();

            keyboardButtonsRow1.add(upOrderButton);
        }

        //todo chat
        InlineKeyboardButton openChatButton = InlineKeyboardButton.builder()
                .callbackData("open chat ye")
                .text("\uD83D\uDCAC Открыть чат")
                .build();

        keyboardButtonsRow1.add(openChatButton);

        rowList.add(keyboardButtonsRow1);

        InlineKeyboardButton backToMainCreateOrderButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_GET_ALL_ORDER_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        keyboardButtonsRow2.add(backToMainCreateOrderButton);

        rowList.add(keyboardButtonsRow2);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .text(getCurrentOrderTextMessage.toString())
                .replyMarkup(inlineKeyboardMarkup)
                .chatId(chatId)
                .messageId(messageId)
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleUpOrder(long chatId, String callbackQueryId, long orderId) {
        Order currentOrder = orderRepository.findById(orderId)
                .orElseThrow();
        List<String> currentOrderPrefixes = null;

        try {
            currentOrderPrefixes = objectMapper.readValue(currentOrder.getPrefixes(), ArrayList.class);
        } catch (JsonProcessingException e) {
            log.warn(e.getMessage());
        }

        if (System.currentTimeMillis() - currentOrder.getLastUppedAt() <= 86_400_000) {
            AnswerCallbackQuery answerCallbackQuery = AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .text("Вы можете поднимать свой заказ один раз в 24 часа")
                    .showAlert(false)
                    .build();

            handleAnswerCallbackQuery(answerCallbackQuery);
            return;
        }

        handleDeleteMessage(DeleteMessage.builder().chatId(telegramBotProperty.getChannelChatId())
                .messageId(currentOrder.getTelegramChannelMessageId()).build());

        orderRepository.updateLastUppedAtById(System.currentTimeMillis(), currentOrder.getId());
        orderRepository.updateTelegramChannelMessageIdById(generateOrderTextAndSend(currentOrder.getId(), currentOrder.getTitle(),
                currentOrder.getDescription(), currentOrder.getBudget(), currentOrderPrefixes), currentOrder.getId());

        AnswerCallbackQuery answerCallbackQuery = AnswerCallbackQuery.builder()
                .text("Вы успешно подняли свой заказ")
                .callbackQueryId(callbackQueryId)
                .showAlert(false)
                .build();

        handleAnswerCallbackQuery(answerCallbackQuery);
    }

    private void handleTakeOrderMessage(long chatId, int messageId, User principalUser, long orderId) {
        if (principalUser.getRole().equals(UserRole.CUSTOMER)) {
            return;
        }

        Order currentOrder = orderRepository.findById(orderId)
                        .orElseThrow();

        List<String> currentOrderPrefixes = null;
        List<String> principalUserPrefixes = null;

        try {
            currentOrderPrefixes = objectMapper.readValue(currentOrder.getPrefixes(), ArrayList.class);
            principalUserPrefixes = objectMapper.readValue(principalUser.getExecutorPrefixes(), ArrayList.class);
        } catch (JsonProcessingException e) {
            log.warn(e.getMessage());
        }

        for (String currentOrderPrefix : currentOrderPrefixes) {
            boolean isExist = false;

            for (String principalUserPrefix : principalUserPrefixes) {
                if (currentOrderPrefix.equals(principalUserPrefix)) {
                    isExist = true;

                    break;
                }
            }

            if (!isExist) {
                handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

                handleSendTextMessage(SendMessage.builder()
                        .chatId(chatId)
                        .text("❌ <b>Вы не можете взять этот заказ, так как у вас нету соответствующего префикса(ов)</b>")
                        .parseMode("html")
                        .build());
                return;
            }
        }

        handleDeleteMessage(DeleteMessage.builder()
                .chatId(telegramBotProperty.getChannelChatId())
                .messageId(currentOrder.getTelegramChannelMessageId())
                .build());

        currentOrder.setOrderStatus(OrderStatus.IN_PROGRESS);
        currentOrder.setExecutorUserId(principalUser.getId());
        currentOrder.setTelegramChannelMessageId(null);

        orderRepository.save(currentOrder);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        //todo chat
        InlineKeyboardButton openChatButton = InlineKeyboardButton.builder()
                .callbackData("open chat")
                .text("\uD83D\uDCAC Открыть чат")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(openChatButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleSendTextMessage(SendMessage.builder()
                .chatId(principalUser.getTelegramId())
                .text("\uD83C\uDF89 <b>Заказ взят в работу!</b>\n\n"
                + "\uD83D\uDD35 <b>Статус:</b> <code>В разработке</code>\n\n"
                + "\uD83D\uDCC4 <b>Заголовок:</b> <code>" + currentOrder.getTitle() + "</code>\n"
                + "\uD83D\uDCDD <b>Описание:</b>\n\n<i>" + currentOrder.getDescription() + "</i>\n\n"
                + "\uD83D\uDCB0 <b>Бюджет:</b> <code>" + (currentOrder.getBudget() == null ? "Договорный" : currentOrder.getBudget() + "$") + "</code>\n\n"
                + "\uD83D\uDEE0\uFE0F <b>Исполнитель приступил к выполнению заказа. Ожидайте результатов!</b>")
                .parseMode("html")
                .replyMarkup(inlineKeyboardMarkup)
                .build());

        handleSendTextMessage(SendMessage.builder()
                .chatId(userRepository.findTelegramIdById(currentOrder.getCustomerUserId()))
                .text("\uD83C\uDF89 <b>Заказ взят в работу!</b>\n\n"
                        + "\uD83D\uDD35 <b>Статус:</b> <code>В разработке</code>\n\n"
                        + "\uD83D\uDCC4 <b>Заголовок:</b> <code>" + currentOrder.getTitle() + "</code>\n"
                        + "\uD83D\uDCDD <b>Описание:</b>\n\n<i>" + currentOrder.getDescription() + "</i>\n\n"
                        + "\uD83D\uDCB0 <b>Бюджет:</b> <code>" + (currentOrder.getBudget() == null ? "Договорный" : currentOrder.getBudget() + "$") + "</code>\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Исполнитель приступил к выполнению заказа. Ожидайте результатов!</b>")
                .parseMode("html")
                .replyMarkup(inlineKeyboardMarkup)
                .build());

        System.out.println("why the mask??");
    }

    //Sys methods

    private ReplyKeyboardMarkup getDefaultReplyKeyboardMarkup(User principalUser) {
        ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();
        replyKeyboardMarkup.setSelective(true);
        replyKeyboardMarkup.setResizeKeyboard(true);
        replyKeyboardMarkup.setOneTimeKeyboard(false);

        KeyboardButton profileButton = new KeyboardButton("\uD83D\uDC64 Профиль");

        KeyboardRow keyboardRow1 = new KeyboardRow();

        keyboardRow1.add(profileButton);

        List<KeyboardRow> keyboardRows = new ArrayList<>();

        keyboardRows.add(keyboardRow1);

        switch (principalUser.getRole()) {
            case CUSTOMER -> {
                KeyboardButton createOrderButton = new KeyboardButton("➕ Создать заказ");
                KeyboardButton myOrdersButton = new KeyboardButton("\uD83D\uDCCB Мои заказы");

                KeyboardRow keyboardRow2 = new KeyboardRow();

                keyboardRow2.add(createOrderButton);
                keyboardRow2.add(myOrdersButton);

                keyboardRows.add(keyboardRow2);
            }
        }

        replyKeyboardMarkup.setKeyboard(keyboardRows);

        return replyKeyboardMarkup;
    }

    private Integer generateOrderTextAndSend(long orderId, String title, String description, Double budget, List<String> prefixes) {
        StringBuilder prefixesString = new StringBuilder();

        for (int i = 0; i < prefixes.size(); i++) {
            if (i == prefixes.size() - 1) {
                prefixesString.append(prefixes.get(i));
            } else {
                prefixesString.append(prefixes.get(i)).append(", ");
            }
        }

        StringBuilder textForTelegramChannel = new StringBuilder();

        textForTelegramChannel.append("\uD83D\uDD35 <b>Активен</b>\n");

        if (prefixes.isEmpty()) {
            textForTelegramChannel.append("\n<b>").append(title).append("</b>\n\n")
                    .append(description).append("\n\n");
        } else {
            textForTelegramChannel.append("\uD83C\uDFA9 <b>").append(prefixesString).append("</b>\n\n")
                    .append("<b>").append(title).append("</b>\n\n")
                    .append(description).append("\n\n");
        }

        if (budget == null) {
            textForTelegramChannel.append("<b>Бюджет:</b> <code>").append("Договорный").append("</code>");
        } else {
            textForTelegramChannel.append("<b>Бюджет:</b> <code>").append(budget).append("$</code>");
        }

        Integer telegramChannelMessageId = null;

        try {
            InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

            InlineKeyboardButton takeButton = InlineKeyboardButton.builder()
                    .url(telegramBotProperty.getBotUrl() + "?start=" + orderId)
                    .text("Беру")
                    .build();

            List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

            keyboardButtonsRow1.add(takeButton);

            List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

            rowList.add(keyboardButtonsRow1);

            inlineKeyboardMarkup.setKeyboard(rowList);

            telegramChannelMessageId = execute(SendMessage.builder()
                    .text(textForTelegramChannel.toString())
                    .chatId(telegramBotProperty.getChannelChatId())
                    .replyMarkup(inlineKeyboardMarkup)
                    .parseMode("html")
                    .build()).getMessageId();
        } catch (TelegramApiException e) {
            log.warn(e.getMessage());
        }

        return telegramChannelMessageId;
    }

    private void handleSendTextMessage(SendMessage message) {
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.warn(e.getMessage());
        }
    }

    private void handleEditMessageText(EditMessageText editMessageText) {
        try {
            execute(editMessageText);
        } catch (TelegramApiException e) {
            log.warn(e.getMessage());
        }
    }

    private void handleDeleteMessage(DeleteMessage deleteMessage) {
        try {
            execute(deleteMessage);
        } catch (TelegramApiException e) {
            log.warn(e.getMessage());
        }
    }

    private void handleAnswerCallbackQuery(AnswerCallbackQuery answerCallbackQuery) {
        try {
            execute(answerCallbackQuery);
        } catch (TelegramApiException e) {
            log.warn(e.getMessage());
        }
    }
}
