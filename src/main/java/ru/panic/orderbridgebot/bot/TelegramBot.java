package ru.panic.orderbridgebot.bot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.panic.orderbridgebot.bot.callback.*;
import ru.panic.orderbridgebot.bot.pojo.*;
import ru.panic.orderbridgebot.model.*;
import ru.panic.orderbridgebot.model.type.*;
import ru.panic.orderbridgebot.payload.type.CryptoToken;
import ru.panic.orderbridgebot.property.CryptoProperty;
import ru.panic.orderbridgebot.property.TelegramBotProperty;
import ru.panic.orderbridgebot.repository.*;
import ru.panic.orderbridgebot.scheduler.CryptoCurrency;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
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
    private final ReplenishmentRepository replenishmentRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final CryptoCurrency cryptoCurrency;
    private final CryptoProperty cryptoProperty;
    private final ExecutorService executorService;
    private final Map<Long, CreateOrderObject> adminCreateOrderSteps = new HashMap<>();
    private final Map<Long, CreateWithdrawalObject> adminCreateWithdrawalSteps = new HashMap<>();
    private final Map<Long, CreateReplenishmentObject> adminCreateReplenishmentSteps = new HashMap<>();
    private final Map<Long, GiveRoleObject> adminGiveRoleSteps = new HashMap<>();
    private final Map<Long, GivePrefixObject> adminGivePrefixSteps = new HashMap<>();
    private final Map<Long, GiveBalanceObject> adminGiveBalanceSteps = new HashMap<>();
    private final Map<Long, Integer> adminGetCurrentUserDataBeginMessageIdSteps = new HashMap<>();
    private final Map<Long, Integer> adminCreateExecutorPrefixBeginMessageIdSteps = new HashMap<>();
    private final Map<Long, Integer> adminDeleteExecutorPrefixBeginMessageIdSteps = new HashMap<>();
    private final Map<Long, Integer> adminBanUserBeginMessageIdSteps = new HashMap<>();
    private final Map<Long, Integer> adminUnbanUserBeginMessageIdSteps = new HashMap<>();
    private final Map<Long, Long> userIdOrderIdJoinedToChats = new HashMap<>();

    public TelegramBot(TelegramBotProperty telegramBotProperty, UserRepository userRepository, OrderRepository orderRepository, PrefixRepository prefixRepository, ReplenishmentRepository replenishmentRepository, WithdrawalRepository withdrawalRepository, MessageRepository messageRepository, ObjectMapper objectMapper, CryptoCurrency cryptoCurrency, CryptoProperty cryptoProperty, ExecutorService executorService) {
        this.telegramBotProperty = telegramBotProperty;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.prefixRepository = prefixRepository;
        this.replenishmentRepository = replenishmentRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.cryptoCurrency = cryptoCurrency;
        this.cryptoProperty = cryptoProperty;
        this.executorService = executorService;

        List<BotCommand> listOfCommands = new ArrayList<>();

        listOfCommands.add(new BotCommand("/start", "\uD83D\uDD04 Перезапустить"));
        listOfCommands.add(new BotCommand("/profile", "\uD83D\uDC64 Профиль"));
        listOfCommands.add(new BotCommand("/rules", "\uD83D\uDCDB Правила использования"));

        try {
            execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            log.warn(e.getMessage());
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
                                    .executorPrefixes("[]")
                                    .isAccountNonLocked(true)
                                    .registeredAt(System.currentTimeMillis())
                                    .build();

                            return userRepository.save(newUser);
                        });

                if (!principalUser.getIsAccountNonLocked()) {
                    handleSendTextMessage(SendMessage.builder()
                            .chatId(chatId)
                            .text("\uD83D\uDEAB <b>Вы были заблокированы администратором</b>")
                            .parseMode("html")
                            .build());
                    return;
                }

                if (text.contains("/start") && text.split(" ").length > 1) {
                    handleTakeOrderMessage(chatId, messageId, principalUser, Long.parseLong(text.split(" ")[1]));
                    return;
                }else if (text.contains("\uD83E\uDD19 Позвать администратора")) {
                    handleUserCallAdmin(chatId, messageId, principalUser);

                    return;
                } else if (text.contains("\uD83D\uDD34 Выйти из текущего чата")) {
                    handleUserLeaveFromChat(chatId, principalUser);
                    return;
                }

                if (userIdOrderIdJoinedToChats.get(principalUser.getId()) != null) {
                    handleUserSendMessageToChat(chatId, messageId, text, principalUser, userIdOrderIdJoinedToChats.get(principalUser.getId()));
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

                    case "/rules", "\uD83D\uDCDB Правила использования" -> {
                        handleRulesMessage(chatId, principalUser);
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

                    case "\uD83D\uDCB0 Пополнить заказ" -> {
                        handleCreateReplenishmentMessage(chatId);
                        return;
                    }

                    case "\uD83D\uDCB8 Вывод средств" -> {
                        handleCreateWithdrawalMessage(chatId);
                        return;
                    }

                    case "\uD83D\uDFE5 Админ-панель" -> {
                        handleCreateAdminMessage(chatId, principalUser);
                        return;
                    }
                }

                if (adminCreateOrderSteps.get(principalUser.getId()) != null) {
                    handleCreateOrderProcess(chatId, messageId, principalUser, text);
                    return;
                }

                if (adminCreateReplenishmentSteps.get(principalUser.getId()) != null) {
                    handleCreateReplenishmentProcess(chatId, messageId, text, principalUser, adminCreateReplenishmentSteps.get(principalUser.getId()));
                    return;
                }

                if (adminCreateWithdrawalSteps.get(principalUser.getId()) != null) {
                    handleCreateWithdrawalProcess(chatId, messageId, text, principalUser, adminCreateWithdrawalSteps.get(principalUser.getId()));
                    return;
                }

                if (adminGiveRoleSteps.get(principalUser.getId()) != null) {
                    handleAdminGiveRoleProcess(chatId, messageId, text, null, principalUser,
                            adminGiveRoleSteps.get(principalUser.getId()));
                    return;
                }

                if (adminGivePrefixSteps.get(principalUser.getId()) != null) {
                    handleAdminGivePrefixProcess(chatId, messageId, text, null, principalUser,
                            adminGivePrefixSteps.get(principalUser.getId()));
                    return;
                }

                if (adminGiveBalanceSteps.get(principalUser.getId()) != null) {
                    handleAdminGiveBalanceProcess(chatId, messageId, text, principalUser,
                            adminGiveBalanceSteps.get(principalUser.getId()));
                    return;
                }

                if (adminGetCurrentUserDataBeginMessageIdSteps.get(principalUser.getId()) != null) {
                    handleAdminGetCurrentUserDataProcess(chatId, messageId, Long.parseLong(text),
                            adminGetCurrentUserDataBeginMessageIdSteps.get(principalUser.getId()),
                            principalUser);
                    return;
                }

                if (adminCreateExecutorPrefixBeginMessageIdSteps.get(principalUser.getId()) != null) {
                    handleAdminCreateExecutorPrefixProcess(chatId, messageId, text, principalUser,
                            adminCreateExecutorPrefixBeginMessageIdSteps.get(principalUser.getId()));
                    return;
                }

                if (adminDeleteExecutorPrefixBeginMessageIdSteps.get(principalUser.getId()) != null) {
                    handleAdminDeleteExecutorPrefixProcess(chatId, messageId, text, principalUser,
                            adminDeleteExecutorPrefixBeginMessageIdSteps.get(principalUser.getId()));
                    return;
                }

                if (adminBanUserBeginMessageIdSteps.get(principalUser.getId()) != null) {
                    handleAdminBanUserProcess(chatId, messageId, text, principalUser,
                            adminBanUserBeginMessageIdSteps.get(principalUser.getId()));

                    return;
                }

                if (adminUnbanUserBeginMessageIdSteps.get(principalUser.getId()) != null) {
                    handleAdminUnbanUserProcess(chatId, messageId, text, principalUser,
                            adminUnbanUserBeginMessageIdSteps.get(principalUser.getId()));

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
                                    .isAccountNonLocked(true)
                                    .registeredAt(System.currentTimeMillis())
                                    .build();

                            return userRepository.save(newUser);
                        });

                if (!principalUser.getIsAccountNonLocked()) {
                    handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                            .callbackQueryId(callbackQueryId)
                            .build());

                    handleSendTextMessage(SendMessage.builder()
                            .chatId(chatId)
                            .text("\uD83D\uDEAB <b>Вы были заблокированы администратором</b>")
                            .parseMode("html")
                            .build());
                    return;
                }


                switch (data) {
                    case "answerQuery" -> {
                        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                                .callbackQueryId(callbackQueryId)
                                .build());
                        return;
                    }


                    case CustomerCallback.CREATE_ORDER_CALLBACK -> {
                        adminCreateOrderSteps.put(principalUser.getId(), CreateOrderObject.builder()
                                .step(1)
                                .beginMessageId(messageId)
                                .prefixes(new ArrayList<>())
                                .build());

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backToMainCreateOrderButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_TO_MAIN_CREATE_ORDER_CALLBACK)
                                .text("↩\uFE0F Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backToMainCreateOrderButton);

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
                        CreateOrderObject createOrderObject = adminCreateOrderSteps.get(principalUser.getId());

                        createOrderObject.setStep(4);

                        adminCreateOrderSteps.put(principalUser.getId(), createOrderObject);

                        handleCreateOrderProcess(chatId, null, principalUser, null);
                        return;
                    }

                    case CustomerCallback.DONT_MIND_CREATE_ORDER_CALLBACK -> {
                        CreateOrderObject createOrderObject = adminCreateOrderSteps.get(principalUser.getId());

                        createOrderObject.setStep(4);
                        createOrderObject.setPrefixes(new ArrayList<>());

                        adminCreateOrderSteps.put(principalUser.getId(), createOrderObject);

                        handleCreateOrderProcess(chatId, null, principalUser, null);
                        return;
                    }

                    case CustomerCallback.DEAL_BUDGET_CREATE_ORDER_CALLBACK -> {
                        CreateOrderObject createOrderObject = adminCreateOrderSteps.get(principalUser.getId());

                        createOrderObject.setStep(5);

                        adminCreateOrderSteps.put(principalUser.getId(), createOrderObject);

                        handleCreateOrderProcess(chatId, null, principalUser, null);

                        return;
                    }

                    case ExecutorCallback.CREATE_WITHDRAWAL_CALLBACK -> {
                        adminCreateWithdrawalSteps.put(principalUser.getId(), CreateWithdrawalObject.builder()
                                .step(1)
                                .beginMessageId(messageId)
                                .build());

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backToMainCreateWithdrawalButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_TO_MAIN_CREATE_WITHDRAWAL_CALLBACK)
                                .text("↩\uFE0F Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backToMainCreateWithdrawalButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        EditMessageText editMessageText = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .text("\uD83D\uDCB2 <b>Укажите сумму вывода</b>\n\n"
                                + "Пожалуйста, укажите сумму, которую вы хотели бы вывести со своего баланса в USD($)\n\n"
                                + "<b>Пример:</b> 100 (для $100)\n\n"
                                + "\uD83D\uDEE0\uFE0F <b>Если у вас возникли вопросы или вам нужна помощь, не стесняйтесь обращаться в нашу поддержку</b>")
                                .replyMarkup(inlineKeyboardMarkup)
                                .parseMode("html")
                                .build();

                        handleEditMessageText(editMessageText);
                        return;
                    }

                    case AdminCallback.ADMIN_GIVE_ROLE_CALLBACK -> {
                        handleAdminGiveRole(chatId, messageId, principalUser);
                    }

                    case AdminCallback.ADMIN_GIVE_PREFIX_CALLBACK -> {
                        handleAdminGivePrefix(chatId, messageId, principalUser);

                        return;
                    }

                    case AdminCallback.ADMIN_GIVE_BALANCE_CALLBACK -> {
                        handleAdminGiveBalance(chatId, messageId, principalUser);

                        return;
                    }

                    case AdminCallback.ADMIN_GET_ALL_USER_DATA_CALLBACK -> {
                        handleAdminGetAllUserData(chatId, messageId, callbackQueryId, principalUser);

                        return;
                    }

                    case AdminCallback.ADMIN_GET_CURRENT_USER_DATA_CALLBACK -> {
                        handleAdminGetCurrentUserData(chatId, messageId, principalUser);
                        return;
                    }

                    case AdminCallback.ADMIN_CREATE_EXECUTOR_PREFIX_CALLBACK -> {
                        handleAdminCreateExecutorPrefix(chatId, messageId, principalUser);
                        return;
                    }

                    case AdminCallback.ADMIN_DELETE_EXECUTOR_PREFIX_CALLBACK -> {
                        handleAdminDeleteExecutorPrefix(chatId, messageId, principalUser);
                        return;
                    }

                    case AdminCallback.ADMIN_BAN_USER_CALLBACK -> {
                        handleAdminBanUser(chatId, messageId, principalUser);
                        return;
                    }

                    case AdminCallback.ADMIN_UNBAN_USER_CALLBACK -> {
                        handleAdminUnbanUser(chatId, messageId, principalUser);
                        return;
                    }

                    case BackCallback.BACK_TO_MAIN_CREATE_ORDER_CALLBACK -> {
                        adminCreateOrderSteps.remove(principalUser.getId());

                        handleEditCreateOrderMessage(chatId, messageId);

                        return;
                    }

                    case BackCallback.BACK_TO_GET_ALL_ORDER_CALLBACK -> {
                        handleEditGetAllOrderMessage(chatId, messageId, principalUser, 1);
                        return;
                    }

                    case BackCallback.BACK_TO_MAIN_CREATE_REPLENISHMENT_CALLBACK -> {
                        adminCreateReplenishmentSteps.remove(principalUser.getId());

                        handleEditCreateReplenishmentGetAllOrderMessage(chatId, messageId);
                        return;
                    }

                    case BackCallback.BACK_TO_MAIN_CREATE_WITHDRAWAL_CALLBACK -> {
                        adminCreateWithdrawalSteps.remove(principalUser.getId());

                        handleEditWithdrawalMessage(chatId, messageId);
                        return;
                    }

                    case BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK -> {
                        adminGiveRoleSteps.remove(principalUser.getId());
                        adminGivePrefixSteps.remove(principalUser.getId());
                        adminGiveBalanceSteps.remove(principalUser.getId());
                        adminGetCurrentUserDataBeginMessageIdSteps.remove(principalUser.getId());
                        adminCreateExecutorPrefixBeginMessageIdSteps.remove(principalUser.getId());
                        adminDeleteExecutorPrefixBeginMessageIdSteps.remove(principalUser.getId());
                        adminBanUserBeginMessageIdSteps.remove(principalUser.getId());
                        adminUnbanUserBeginMessageIdSteps.remove(principalUser.getId());

                        handleEditAdminMessage(chatId, messageId, principalUser);
                    }
                }

                if (data.contains(BackCallback.BACK_TO_ADMIN_GET_CURRENT_ORDER_CALLBACK)) {
                    long orderId = Long.parseLong(data.split(" ")[7]);

                    handleAdminGetCurrentOrder(chatId, messageId, orderId, principalUser);
                    return;
                }


                if (data.contains(CustomerCallback.SELECT_PREFIX_CREATE_ORDER_CALLBACK)) {
                    long prefixId = Long.parseLong(data.split(" ")[5]);

                    Prefix prefix = prefixRepository.findById(prefixId).orElseThrow();

                    CreateOrderObject createOrderObject = adminCreateOrderSteps.get(principalUser.getId());

                    createOrderObject.setStep(2);

                    List<String> prefixes = createOrderObject.getPrefixes();

                    if (prefixes.contains(prefix.getName())) {
                        prefixes.remove(prefix.getName());
                    } else {
                        prefixes.add(prefix.getName());
                    }

                    createOrderObject.setPrefixes(prefixes);

                    adminCreateOrderSteps.put(principalUser.getId(), createOrderObject);

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

                if (data.contains(CustomerCallback.DELETE_ORDER_CALLBACK)) {
                    long orderId = Long.parseLong(data.split(" ")[3]);

                    handleDeleteOrder(chatId, messageId, callbackQueryId, principalUser, orderId);
                    return;
                };


                if (data.contains(UserCallback.GET_ALL_ORDER_CALLBACK)) {
                    int page = Integer.parseInt(data.split(" ")[4]);

                    handleEditGetAllOrderMessage(chatId, messageId, principalUser, page);
                    return;
                }


                if (data.contains(CustomerCallback.CREATE_REPLENISHMENT_GET_ALL_ORDER_CALLBACK)) {
                    int page = Integer.parseInt(data.split(" ")[6]);

                    handleEditCreateReplenishmentGetAllOrderMessage(chatId, messageId, principalUser, page);
                    return;
                }

                if (data.contains(CustomerCallback.CREATE_REPLENISHMENT_GET_CURRENT_ORDER_CALLBACK)) {
                    long orderId = Long.parseLong(data.split(" ")[6]);

                    handleCreateReplenishmentGetCurrentOrder(chatId, messageId, principalUser, orderId);
                    return;
                }

                if (data.contains(CustomerCallback.CREATE_REPLENISHMENT_SELECT_PAYMENT_METHOD_CALLBACK)) {
                    PaymentMethod paymentMethod = PaymentMethod.valueOf(data.split(" ")[6]);

                    handleCreateReplenishmentSelectPaymentMethod(chatId, principalUser, paymentMethod);
                    return;
                }

                if (data.contains(CustomerCallback.CREATE_REPLENISHMENT_ACCEPT_CALLBACK)) {
                    long replenishmentId = Long.parseLong(data.split(" ")[4]);

                    handleCreateReplenishmentAccept(chatId, messageId, principalUser, update.getCallbackQuery().getFrom().getFirstName(), replenishmentId);
                    return;
                }

                if (data.contains(CustomerCallback.CREATE_REPLENISHMENT_REFUSE_CALLBACK)) {
                    long replenishmentId = Long.parseLong(data.split(" ")[4]);

                    handleCreateReplenishmentRefuse(chatId, messageId, replenishmentId);
                    return;
                }

                if (data.contains(ExecutorCallback.CREATE_WITHDRAWAL_ACCEPT_CALLBACK)) {
                    long withdrawalId = Long.parseLong(data.split(" ")[4]);

                    handleCreateWithdrawalAccept(chatId, messageId, callbackQueryId, principalUser, update.getCallbackQuery().getFrom().getFirstName(),
                            withdrawalId);
                    return;
                }

                if (data.contains(ExecutorCallback.CREATE_WITHDRAWAL_REFUSE_CALLBACK)) {
                    long withdrawalId = Long.parseLong(data.split(" ")[4]);

                    handleCreateWithdrawalRefuse(chatId, messageId, withdrawalId);
                    return;
                }

                if (data.contains(AdminCallback.ACCEPT_REPLENISHMENT_CALLBACK)) {
                    long replenishmentId = Long.parseLong(data.split(" ")[3]);

                    handleAdminAcceptReplenishment(chatId, messageId, replenishmentId, callbackQueryId);
                    return;
                }

                if (data.contains(ExecutorCallback.CREATE_WITHDRAWAL_SELECT_PAYMENT_METHOD_CALLBACK)) {
                    PaymentMethod paymentMethod = PaymentMethod.valueOf(data.split(" ")[6]);

                    handleCreateWithdrawalSelectPaymentMethod(chatId, paymentMethod, principalUser);
                    return;
                }

                if (data.contains(AdminCallback.REFUSE_REPLENISHMENT_CALLBACK)) {
                    long replenishmentId = Long.parseLong(data.split(" ")[3]);

                    handleAdminRefuseReplenishment(chatId, messageId, replenishmentId, callbackQueryId);
                    return;
                }

                if (data.contains(AdminCallback.ACCEPT_WITHDRAWAL_CALLBACK)) {
                    long withdrawalId = Long.parseLong(data.split(" ")[3]);

                    handleAdminAcceptWithdrawal(chatId, messageId, withdrawalId, callbackQueryId);
                    return;
                }

                if (data.contains(AdminCallback.REFUSE_WITHDRAWAL_CALLBACK)) {
                    long withdrawalId = Long.parseLong(data.split(" ")[3]);

                    handleAdminRefuseWithdrawal(chatId, messageId, withdrawalId, callbackQueryId);
                    return;
                }

                if (data.contains(AdminCallback.ADMIN_GET_ALL_ORDER_CALLBACK)) {
                    int page = Integer.parseInt(data.split(" ")[5]);

                    handleAdminGetAllOrder(chatId, messageId, page);
                    return;
                }

                if (data.contains(AdminCallback.ADMIN_GET_ALL_IN_PROGRESS_ORDER_CALLBACK)) {
                    int page = Integer.parseInt(data.split(" ")[7]);

                    handleAdminGetAllInProgressOrder(chatId, messageId, page);
                    return;
                }

                if (data.contains(AdminCallback.ADMIN_GET_CURRENT_ORDER_CALLBACK)) {
                    long orderId = Long.parseLong(data.split(" ")[5]);

                    handleAdminGetCurrentOrder(chatId, messageId, orderId, principalUser);
                    return;
                }

                if (data.contains(AdminCallback.ADMIN_DESTROY_ORDER_CALLBACK)) {
                    long orderId = Long.parseLong(data.split(" ")[4]);

                    handleAdminDestroyOrder(chatId, messageId, callbackQueryId, principalUser, orderId);
                    return;
                }

                if (data.contains(AdminCallback.ADMIN_UPDATE_ORDER_CALLBACK)) {
                    long orderId = Long.parseLong(data.split(" ")[4]);

                    handleAdminUpdateOrder(chatId, messageId, callbackQueryId, principalUser, orderId);
                    return;
                }

                if (data.contains(AdminCallback.ADMIN_UPDATE_ORDER_STATUS_CALLBACK)) {
                    long orderId = Long.parseLong(data.split(" ")[5]);

                    handleAdminUpdateOrderStatus(chatId, messageId, orderId);
                    return;
                }

                if (data.contains(AdminCallback.ADMIN_SELECT_ORDER_STATUS_CALLBACK)) {
                    String[] dataSplitStrings = data.split(" ");

                    long orderId = Long.parseLong(dataSplitStrings[5]);
                    OrderStatus orderStatus = OrderStatus.valueOf(dataSplitStrings[6]);

                    handleAdminSetOrderStatus(chatId, messageId, callbackQueryId, orderId, orderStatus, principalUser);
                    return;
                }


                if (data.contains(AdminCallback.ADMIN_SELECT_ROLE_CALLBACK)) {
                    UserRole userRole = UserRole.valueOf(data.split(" ")[4]);

                    GiveRoleObject giveRoleObject = adminGiveRoleSteps.get(principalUser.getId());

                    giveRoleObject.setStep(3);
                    giveRoleObject.setUserRole(userRole);

                    adminGiveRoleSteps.put(principalUser.getId(), giveRoleObject);

                    handleAdminGiveRoleProcess(chatId, messageId, null, callbackQueryId,
                            principalUser, giveRoleObject);
                    return;
                }

                if (data.contains(AdminCallback.ADMIN_SELECT_PREFIX_CALLBACK)) {
                    long prefixId = Long.parseLong(data.split(" ")[4]);

                    GivePrefixObject givePrefixObject = adminGivePrefixSteps.get(principalUser.getId());

                    givePrefixObject.setStep(3);
                    givePrefixObject.setPrefixId(prefixId);

                    adminGivePrefixSteps.put(principalUser.getId(), givePrefixObject);

                    handleAdminGivePrefixProcess(chatId, messageId, null, callbackQueryId,
                            principalUser, givePrefixObject);
                    return;
                }

                if (data.contains(AdminCallback.ADMIN_GET_ALL_CHAT_MESSAGE_CALLBACK)) {
                    long orderId = Long.parseLong(data.split(" ")[6]);

                    handleAdminGetAllChatMessage(chatId, callbackQueryId, principalUser, orderId);
                    return;
                }

                if (data.contains(UserCallback.JOIN_TO_CHAT_CALLBACK)) {
                    String[] dataSplits = data.split(" ");

                    long userId = Long.parseLong(dataSplits[4]);
                    long orderId = Long.parseLong(dataSplits[5]);

                    handleUserJoinToChat(chatId, callbackQueryId, principalUser, orderId);
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
                .text("\uD83D\uDC4B Приветствую вас в <b>NexusProject!</b>\n\n"
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

        String executorStatusString = "";

        if (principalUser.getRole().equals(UserRole.EXECUTOR)) {
            String[] principalPrefixes = null;
            try {
                principalPrefixes = objectMapper.readValue(principalUser.getExecutorPrefixes(), String[].class);
            } catch (JsonProcessingException e) {
                log.warn(e.getMessage());
            }

            if (principalPrefixes.length != 0) {
                executorStatusString = "ℹ\uFE0F <b>Префиксы исполнителя:</b> <code>" + String.join(", ", principalPrefixes) + "</code>\n\n";
            }
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

    private void handleRulesMessage(long chatId, User principalUser) {
        switch (principalUser.getRole()) {
            case EXECUTOR -> {
                handleSendTextMessage(SendMessage.builder()
                        .chatId(chatId)
                        .text("\uD83D\uDCDB <b>Правила использования</b>\n\n"
                                + "Здесь будут правила для заказчика")
                        .parseMode("html")
                        .build());
            }

            case ADMIN -> {
                handleSendTextMessage(SendMessage.builder()
                        .chatId(chatId)
                        .text("\uD83D\uDCDB <b>Правила использования</b>\n\n"
                                + "Здесь будут правила для админа")
                        .parseMode("html")
                        .build());
            }

            case CUSTOMER -> {
                handleSendTextMessage(SendMessage.builder()
                        .chatId(chatId)
                        .text("\uD83D\uDCDB <b>Правила использования</b>\n\n"
                                + "Здесь будут правила для исполнителя")
                        .parseMode("html")
                        .build());
            }
        }
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
        CreateOrderObject createOrderObject = adminCreateOrderSteps.get(principalUser.getId());

        switch (createOrderObject.getStep()) {
            case 1 -> {
                createOrderObject.setStep(2);
                createOrderObject.setTitle(text);

                adminCreateOrderSteps.put(principalUser.getId(), createOrderObject);

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

                adminCreateOrderSteps.put(principalUser.getId(), createOrderObject);

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
                                + "\uD83D\uDD0D <b>Пожалуйста,</b> выберите один или несколько из следующих префиксов. Если вы не уверены, какой именно исполнитель и с какими навыками вам нужен, выбирайте \"Нет понимания\"\n\n"
                                + "\uD83D\uDEE0\uFE0F <b>Мы готовы перейти к следующему шагу!</b>")
                        .build();

                handleEditMessageText(editMessageText);
            }

            case 4 -> {
                createOrderObject.setStep(5);

                adminCreateOrderSteps.put(principalUser.getId(), createOrderObject);

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
                                + "\uD83D\uDCBC Если у вас есть какие-либо дополнительные вопросы или требуется изменение заказа, не стесняйтесь обращаться в нашу поддержку\n\n"
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
                + "\uD83D\uDEE0\uFE0F <b>Если у вас есть какие-либо вопросы или вам нужна помощь, не стесняйтесь обращаться в нашу поддержку</b>")
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleSendTextMessage(sendMessage);
    }

    private void handleCreateReplenishmentMessage(long chatId) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton createReplenishmentButton = InlineKeyboardButton.builder()
                .callbackData(CustomerCallback.CREATE_REPLENISHMENT_GET_ALL_ORDER_CALLBACK + " " + 1)
                .text("\uD83D\uDCB0 Пополнить заказ")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(createReplenishmentButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .text("\uD83D\uDCB0 <b>Пополнить заказ</b>\n\n"
                + "Просто нажмите кнопку ниже, чтобы пополнить бюджет своего заказа и обеспечить исполнителя дополнительными средствами для выполнения задания\n\n"
                + "\uD83D\uDEE0\uFE0F <b>Успешного сотрудничества!</b>")
                .parseMode("html")
                .build();

        handleSendTextMessage(message);
    }

    private void handleEditCreateReplenishmentGetAllOrderMessage(long chatId, int messageId) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton createReplenishmentButton = InlineKeyboardButton.builder()
                .callbackData(CustomerCallback.CREATE_REPLENISHMENT_GET_ALL_ORDER_CALLBACK + " " + 1)
                .text("\uD83D\uDCB0 Пополнить заказ")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(createReplenishmentButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .replyMarkup(inlineKeyboardMarkup)
                .text("\uD83D\uDCB0 <b>Пополнить заказ</b>\n\n"
                        + "Просто нажмите кнопку ниже, чтобы пополнить бюджет своего заказа и обеспечить исполнителя дополнительными средствами для выполнения задания\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Успешного сотрудничества!</b>")
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleCreateReplenishmentGetCurrentOrder(long chatId, int messageId, User principalUser, long orderId) {
        Order currentOrder = orderRepository.findById(orderId)
                .orElseThrow();

        adminCreateReplenishmentSteps.put(principalUser.getId(), CreateReplenishmentObject.builder()
                .beginMessageId(messageId)
                .orderId(orderId)
                .step(1)
                .build());

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton createReplenishmentButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_CREATE_REPLENISHMENT_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(createReplenishmentButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .replyMarkup(inlineKeyboardMarkup)
                .text("\uD83D\uDCB2 <b>Укажите сумму оплаты</b>\n\n"
                + "Вы выбрали заказ <code>\"" + currentOrder.getTitle() + "\"</code> для пополнения бюджета. Теперь, пожалуйста, укажите сумму оплаты в долларах США ($)\n\n"
                + "<b>Пример:</b> 500 (для $500)\n\n"
                + "\uD83D\uDEE0\uFE0F <b>Если у вас возникли вопросы или вам нужна помощь, не стесняйтесь обращаться в нашу поддержку</b>")
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
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

        EditMessageText editMessageText = EditMessageText.builder()
                .text("\uD83D\uDCCB <b>Мои заказы</b>\n\n"
                        + "<b>Добро пожаловать в раздел ваших заказов!</b> Здесь вы можете просматривать и управлять своими активными заказами\n\n"
                        + "\uD83D\uDCC4 Для просмотра всех ваших заказов, выберите соответствующую страницу с помощью кнопок ниже\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Если у вас есть какие-либо вопросы или вам нужна помощь, не стесняйтесь обращаться в нашу поддержку</b>")
                .chatId(chatId)
                .messageId(messageId)
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleEditCreateReplenishmentGetAllOrderMessage(long chatId, int messageId, User principalUser, int page) {
        List<Order> principalOrders = orderRepository
                .findAllByCustomerUserIdAndOrderStatusWithOffsetOrderByCreatedAtDesc(principalUser.getId(),
                        OrderStatus.IN_PROGRESS,
                        6,
                        6 * (page - 1));

        long principalOrdersCount = orderRepository.countByCustomerUserIdAndOrderStatus(principalUser.getId(),
                OrderStatus.IN_PROGRESS);

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
                    .callbackData(CustomerCallback.CREATE_REPLENISHMENT_GET_CURRENT_ORDER_CALLBACK + " " + order.getId())
                    .text(order.getTitle() + " / " + (order.getBudget() == null ? "Договорный" : order.getBudget() + "$")
                            + " / " + orderStatusString)
                    .build();

            orderKeyboardButtonsRow.add(orderButton);

            rowList.add(orderKeyboardButtonsRow);
        }

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        if (page != 1) {
            InlineKeyboardButton prevButton = InlineKeyboardButton.builder()
                    .callbackData(CustomerCallback.CREATE_REPLENISHMENT_GET_ALL_ORDER_CALLBACK + " " + (page - 1))
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
                    .callbackData(CustomerCallback.CREATE_REPLENISHMENT_GET_ALL_ORDER_CALLBACK + " " + (page + 1))
                    .text("⏩ Следующая")
                    .build();

            keyboardButtonsRow1.add(nextButton);
        }

        rowList.add(keyboardButtonsRow1);

        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();

        InlineKeyboardButton backToMainCreateReplenishmentButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_CREATE_REPLENISHMENT_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        keyboardButtonsRow2.add(backToMainCreateReplenishmentButton);

        rowList.add(keyboardButtonsRow2);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .replyMarkup(inlineKeyboardMarkup)
                .text("\uD83D\uDCB0 <b>Выберите заказ для пополнения бюджета:</b>")
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

        if (currentOrder.getOrderStatus().equals(OrderStatus.ACTIVE) &&
                currentOrder.getCustomerUserId().equals(principalUser.getId())) {
            InlineKeyboardButton upOrderButton = InlineKeyboardButton.builder()
                    .callbackData(CustomerCallback.UP_ORDER_CALLBACK + " " + orderId)
                    .text("\uD83C\uDD99 Поднять заказ")
                    .build();

            keyboardButtonsRow1.add(upOrderButton);
        }

        //todo chat
        InlineKeyboardButton openChatButton = InlineKeyboardButton.builder()
                .callbackData(UserCallback.JOIN_TO_CHAT_CALLBACK + " " + principalUser.getId() + " " + orderId)
                .text("\uD83D\uDCAC Открыть чат")
                .build();

        keyboardButtonsRow1.add(openChatButton);

        rowList.add(keyboardButtonsRow1);

        if (currentOrder.getOrderStatus().equals(OrderStatus.ACTIVE)) {
            List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();

            InlineKeyboardButton deleteOrderButton = InlineKeyboardButton.builder()
                    .callbackData(CustomerCallback.DELETE_ORDER_CALLBACK + " " + currentOrder.getId())
                    .text("❌ Удалить заказ")
                    .build();

            keyboardButtonsRow3.add(deleteOrderButton);

            rowList.add(keyboardButtonsRow3);
        }

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
            handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

            handleSendTextMessage(SendMessage.builder()
                    .chatId(chatId)
                    .text("❌ <b>Вы не можете взять этот заказ, так как вы не являетесь исполнителем</b>")
                    .parseMode("html")
                    .build());
            return;
        }

        Order currentOrder = orderRepository.findById(orderId)
                        .orElseThrow();

        if (currentOrder.getExecutorUserId() != null) {
            handleSendTextMessage(SendMessage.builder()
                    .text("❌ <b>Вы не можете взять этот заказ, так как у него уже имеется исполнитель</b>")
                    .chatId(chatId)
                    .parseMode("html")
                    .build());
            return;
        }

        List<String> currentOrderPrefixes = null;
        List<String> principalUserPrefixes = null;

        try {
            currentOrderPrefixes = objectMapper.readValue(currentOrder.getPrefixes(), ArrayList.class);
            principalUserPrefixes = objectMapper.readValue(principalUser.getExecutorPrefixes(), ArrayList.class);
        } catch (JsonProcessingException e) {
            log.warn(e.getMessage());
        }

        boolean principalHasOneCorrectPrefix = false;

        firstLoop: for (String currentOrderPrefix : currentOrderPrefixes) {
            for (String principalUserPrefix : principalUserPrefixes) {
                if (currentOrderPrefix.equals(principalUserPrefix)) {
                    principalHasOneCorrectPrefix = true;
                    break firstLoop;
                }
            }
        }

        if (!principalHasOneCorrectPrefix) {
            handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

            handleSendTextMessage(SendMessage.builder()
                    .chatId(chatId)
                    .text("❌ <b>Вы не можете взять этот заказ, так как у вас нету соответствующего префикса(ов)</b>")
                    .parseMode("html")
                    .build());

            return;
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
                .callbackData(UserCallback.JOIN_TO_CHAT_CALLBACK + " " + principalUser.getId() + " " + orderId)
                .text("\uD83D\uDCAC Открыть чат")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(openChatButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        //send message to executor
        handleSendTextMessage(SendMessage.builder()
                .chatId(principalUser.getTelegramId())
                .text("\uD83C\uDF89 <b>Заказ взят в работу!</b>\n\n"
                + "\uD83D\uDD35 <b>Статус:</b> <code>В разработке</code>\n\n"
                + "\uD83D\uDCC4 <b>Заголовок:</b> <code>" + currentOrder.getTitle() + "</code>\n"
                + "\uD83D\uDCDD <b>Описание:</b>\n\n<i>" + currentOrder.getDescription() + "</i>\n\n"
                + "\uD83D\uDCB0 <b>Бюджет:</b> <code>" + (currentOrder.getBudget() == null ? "Договорный" : currentOrder.getBudget() + "$") + "</code>\n\n"
                + "\uD83D\uDEE0\uFE0F <b>Теперь, когда все готово, вы можете приступить к обсуждению этого проекта с заказчиком</b>")
                .parseMode("html")
                .replyMarkup(inlineKeyboardMarkup)
                .build());

        //send message to customer
        handleSendTextMessage(SendMessage.builder()
                .chatId(userRepository.findTelegramIdById(currentOrder.getCustomerUserId()))
                .text("\uD83C\uDF89 <b>Заказ взят в работу!</b>\n\n"
                        + "\uD83D\uDD35 <b>Статус:</b> <code>В разработке</code>\n\n"
                        + "\uD83D\uDCC4 <b>Заголовок:</b> <code>" + currentOrder.getTitle() + "</code>\n"
                        + "\uD83D\uDCDD <b>Описание:</b>\n\n<i>" + currentOrder.getDescription() + "</i>\n\n"
                        + "\uD83D\uDCB0 <b>Бюджет:</b> <code>" + (currentOrder.getBudget() == null ? "Договорный" : currentOrder.getBudget() + "$") + "</code>\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Теперь, когда все готово, вы можете приступить к обсуждению этого проекта с исполнителем</b>")
                .parseMode("html")
                .replyMarkup(inlineKeyboardMarkup)
                .build());
    }

    private void handleDeleteOrder(long chatId, int messageId, String callbackQueryId, User principalUser, long orderId) {
        Order currentOrder = orderRepository.findById(orderId)
                .orElseThrow();

        if (currentOrder.getTelegramChannelMessageId() != null) {
            handleDeleteMessage(DeleteMessage.builder()
                    .chatId(telegramBotProperty.getChannelChatId())
                    .messageId(currentOrder.getTelegramChannelMessageId())
                    .build());
        }

        if (userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()) != null
                && userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()).equals(currentOrder.getId())) {
            userIdOrderIdJoinedToChats.remove(currentOrder.getCustomerUserId());
        }

        if (currentOrder.getExecutorUserId() != null
                && userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()) != null
                && userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()).equals(currentOrder.getId())) {
            userIdOrderIdJoinedToChats.remove(currentOrder.getExecutorUserId());
        }

        messageRepository.deleteAllById(messageRepository.findAllIdByOrderId(currentOrder.getId()));
        replenishmentRepository.deleteAllById(replenishmentRepository.findAllIdByOrderId(currentOrder.getId()));
        orderRepository.delete(currentOrder);

        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text("Вы успешно удалили свой заказ")
                .build());

        handleEditGetAllOrderMessage(chatId, messageId, principalUser, 1);
    }

    private void handleCreateReplenishmentProcess(long chatId, Integer messageId, String text, User principalUser, CreateReplenishmentObject createReplenishmentObject) {
        switch (createReplenishmentObject.getStep()) {
            case 1 -> {
                double amount = Double.parseDouble(text);

                createReplenishmentObject.setStep(2);
                createReplenishmentObject.setAmount(amount);

                adminCreateReplenishmentSteps.put(principalUser.getId(), createReplenishmentObject);

                handleDeleteMessage(DeleteMessage.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .build());

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();

                InlineKeyboardButton btcPaymentReplenishmentButton = InlineKeyboardButton.builder()
                        .callbackData(CustomerCallback.CREATE_REPLENISHMENT_SELECT_PAYMENT_METHOD_CALLBACK + " " + PaymentMethod.BTC)
                        .text("BTC")
                        .build();

                InlineKeyboardButton ltcPaymentReplenishmentButton = InlineKeyboardButton.builder()
                        .callbackData(CustomerCallback.CREATE_REPLENISHMENT_SELECT_PAYMENT_METHOD_CALLBACK + " " + PaymentMethod.LTC)
                        .text("LTC")
                        .build();

                InlineKeyboardButton trc20PaymentReplenishmentButton = InlineKeyboardButton.builder()
                        .callbackData(CustomerCallback.CREATE_REPLENISHMENT_SELECT_PAYMENT_METHOD_CALLBACK + " " + PaymentMethod.TRC20)
                        .text("TRC20")
                        .build();

                InlineKeyboardButton backToMainCreateReplenishmentButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_CREATE_REPLENISHMENT_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                keyboardButtonsRow1.add(btcPaymentReplenishmentButton);
                keyboardButtonsRow2.add(ltcPaymentReplenishmentButton);
                keyboardButtonsRow3.add(trc20PaymentReplenishmentButton);
                keyboardButtonsRow4.add(backToMainCreateReplenishmentButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);
                rowList.add(keyboardButtonsRow2);
                rowList.add(keyboardButtonsRow3);
                rowList.add(keyboardButtonsRow4);

                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(createReplenishmentObject.getBeginMessageId())
                        .replyMarkup(inlineKeyboardMarkup)
                        .text("\uD83D\uDCB3 <b>Выберите метод оплаты</b>\n\n"
                        + "Пожалуйста, выберите удобный для вас метод оплаты из списка ниже\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Если у вас возникли вопросы или вам нужна помощь, не стесняйтесь обращаться в нашу поддержку</b>")
                        .parseMode("html")
                        .build();

                handleEditMessageText(editMessageText);
            }
            case 3 -> {
                adminCreateReplenishmentSteps.remove(principalUser.getId());

                Replenishment newReplenishment = Replenishment.builder()
                        .userId(principalUser.getId())
                        .orderId(createReplenishmentObject.getOrderId())
                        .paymentAmount(createReplenishmentObject.getAmount())
                        .status(ReplenishmentStatus.PENDING)
                        .method(createReplenishmentObject.getPaymentMethod())
                        .createdAt(System.currentTimeMillis())
                        .build();


                BigDecimal replenishmentAmount = null;

                switch (createReplenishmentObject.getPaymentMethod()) {
                    case BTC -> {
                        replenishmentAmount = BigDecimal.valueOf(createReplenishmentObject.getAmount() / cryptoCurrency.getUsdPrice().get(CryptoToken.BTC));

                        replenishmentAmount = replenishmentAmount.setScale(7, RoundingMode.HALF_UP);

                        newReplenishment.setAmount(replenishmentAmount.toPlainString());
                    }
                    case LTC -> {
                        replenishmentAmount = BigDecimal.valueOf(createReplenishmentObject.getAmount() / cryptoCurrency.getUsdPrice().get(CryptoToken.LTC));

                        replenishmentAmount = replenishmentAmount.setScale(4, RoundingMode.HALF_UP);

                        newReplenishment.setAmount(replenishmentAmount.toPlainString());
                    }
                    case TRC20 -> {
                        replenishmentAmount = BigDecimal.valueOf(createReplenishmentObject.getAmount() / cryptoCurrency.getUsdPrice().get(CryptoToken.USDT));

                        replenishmentAmount = replenishmentAmount.setScale(2, RoundingMode.HALF_UP);

                        newReplenishment.setAmount(replenishmentAmount.toPlainString());
                    }
                }

                newReplenishment = replenishmentRepository.save(newReplenishment);

                StringBuilder textForCreateReplenishment = new StringBuilder();

                textForCreateReplenishment.append("\uD83D\uDCB3 <b>Данные для оплаты</b>\n\n")
                        .append("Вы выбрали метод оплаты: <b>").append(createReplenishmentObject.getPaymentMethod()).append("</b>\n\n")
                        .append("\uD83D\uDD39 <b>Адрес получателя:</b> <code>");

                switch (createReplenishmentObject.getPaymentMethod()) {
                    case BTC -> textForCreateReplenishment.append(cryptoProperty.getBtcAddress()).append("</code>\n");
                    case LTC -> textForCreateReplenishment.append(cryptoProperty.getLtcAddress()).append("</code>\n");
                    case TRC20 -> textForCreateReplenishment.append(cryptoProperty.getTrc20Address()).append("</code>\n");
                }

                textForCreateReplenishment.append("\uD83D\uDCB0 <b>Сумма в ").append(createReplenishmentObject.getPaymentMethod()).append(":</b> <code>").append(newReplenishment.getAmount()).append("</code>\n\n")
                        .append("\uD83D\uDD10 Пожалуйста, используйте указанные выше данные для осуществления платежа\n\n")
                        .append("\uD83D\uDEE0\uFE0F <b>Если у вас возникли вопросы или вам нужна помощь, не стесняйтесь обращаться в нашу поддержку</b>");

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                InlineKeyboardButton acceptReplenishmentButton = InlineKeyboardButton.builder()
                        .callbackData(CustomerCallback.CREATE_REPLENISHMENT_ACCEPT_CALLBACK + " " + newReplenishment.getId())
                        .text("✅ Оплатил")
                        .build();

                InlineKeyboardButton refuseReplenishmentButton = InlineKeyboardButton.builder()
                        .callbackData(CustomerCallback.CREATE_REPLENISHMENT_REFUSE_CALLBACK + " " + newReplenishment.getId())
                        .text("❌ Отменить")
                        .build();

                keyboardButtonsRow1.add(acceptReplenishmentButton);
                keyboardButtonsRow1.add(refuseReplenishmentButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);

                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(createReplenishmentObject.getBeginMessageId())
                        .replyMarkup(inlineKeyboardMarkup)
                        .text(textForCreateReplenishment.toString())
                        .parseMode("html")
                        .build();

                handleEditMessageText(editMessageText);
            }
        }
    }

    private void handleCreateReplenishmentSelectPaymentMethod(long chatId, User principalUser, PaymentMethod paymentMethod) {
        CreateReplenishmentObject createReplenishmentObject = adminCreateReplenishmentSteps.get(principalUser.getId());

        createReplenishmentObject.setStep(3);
        createReplenishmentObject.setPaymentMethod(paymentMethod);

        adminCreateReplenishmentSteps.put(principalUser.getId(), createReplenishmentObject);

        handleCreateReplenishmentProcess(chatId, null, null, principalUser, createReplenishmentObject);
    }

    private void handleCreateReplenishmentAccept(long chatId, int messageId, User principalUser, String firstName, long replenishmentId) {
        Replenishment currentReplenishment = replenishmentRepository.findById(replenishmentId)
                        .orElseThrow();

        Order currentOrder = orderRepository.findById(currentReplenishment.getOrderId())
                        .orElseThrow();

        replenishmentRepository.updateStatusById(ReplenishmentStatus.IN_PROCESS, replenishmentId);

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("✅ <b>Вы успешно оплатили платеж для заказа <code>\"" + currentOrder.getTitle() + "\"</code>. Ожидайте подтверждения со стороны администрации</b>")
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        InlineKeyboardButton acceptReplenishmentButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ACCEPT_REPLENISHMENT_CALLBACK + " " + replenishmentId)
                .text("✅ Принять")
                .build();

        InlineKeyboardButton refuseReplenishmentButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.REFUSE_REPLENISHMENT_CALLBACK + " " + replenishmentId)
                .text("❌ Отклонить")
                .build();

        keyboardButtonsRow1.add(acceptReplenishmentButton);
        keyboardButtonsRow1.add(refuseReplenishmentButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage message = SendMessage.builder()
                .chatId(telegramBotProperty.getChatReplenishmentNotificationChatId())
                .replyMarkup(inlineKeyboardMarkup)
                .text("\uD83C\uDF89 <b>Новая заявка на пополнение</b>\n\n"
                + "\uD83D\uDD11 <b>ID:</b> <code>" + principalUser.getTelegramId() + "</code>\n"
                + "\uD83E\uDEAA <b>Имя:</b> <code>" + firstName + "</code>\n\n"
                + "\uD83D\uDD24 <b>Заголовок заказа:</b> <code>" + currentOrder.getTitle() + "</code>\n\n"
                + "ℹ\uFE0F <b>Метод оплаты:</b> <code>" + currentReplenishment.getMethod() + "</code>\n"
                + "\uD83D\uDCB0 <b>Сумма:</b> <code>" + currentReplenishment.getAmount() + "</code>\n"
                + "\uD83D\uDCB5 <b>Сумма в USD:</b> <code>" + currentReplenishment.getPaymentAmount() + "</code>")
                .parseMode("html")
                .build();

        handleSendTextMessage(message);
    }

    private void handleCreateWithdrawalAccept(long chatId, int messageId, String callbackQueryId, User principalUser, String firstName,
                                                 long withdrawalId) {
        Withdrawal currentWithdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow();

        withdrawalRepository.updateStatusById(WithdrawalStatus.PENDING, withdrawalId);

        if (currentWithdrawal.getPaymentAmount() > principalUser.getBalance()) {
            handleSendTextMessage(SendMessage.builder()
                    .chatId(chatId)
                    .text("❌ <b>У вас не хватает баланса для создания заявки на выплату</b>")
                    .parseMode("html")
                    .build());

            handleAnswerCallbackQuery(AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build());
            return;
        }

        userRepository.updateBalanceById(principalUser.getBalance() - currentWithdrawal.getPaymentAmount(),
                principalUser.getId());

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("✅ <b>Вы успешно опубликовали свою заявку на вывод. Ожидайте подтверждения со стороны администрации</b>")
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        InlineKeyboardButton acceptReplenishmentButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ACCEPT_WITHDRAWAL_CALLBACK + " " + currentWithdrawal.getId())
                .text("✅ Принять")
                .build();

        InlineKeyboardButton refuseReplenishmentButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.REFUSE_WITHDRAWAL_CALLBACK + " " + currentWithdrawal.getId())
                .text("❌ Отклонить")
                .build();

        keyboardButtonsRow1.add(acceptReplenishmentButton);
        keyboardButtonsRow1.add(refuseReplenishmentButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage sendAdminMessage = SendMessage.builder()
                .chatId(telegramBotProperty.getChatWithdrawalNotificationChatId())
                .text("\uD83C\uDF89 <b>Новая заявка на вывод</b>\n\n"
                + "\uD83D\uDD11 <b>ID:</b> <code>" + principalUser.getTelegramId() + "</code>\n"
                + "\uD83E\uDEAA <b>Имя:</b> <code>" + firstName + "</code>\n\n"
                + "\uD83C\uDF10 <b>Адрес в крипто-сети:</b> <code>" + currentWithdrawal.getAddress() + "</code>\n\n"
                + "ℹ\uFE0F <b>Метод оплаты:</b> <code>" + currentWithdrawal.getPaymentMethod() + "</code>\n"
                + "\uD83D\uDCB0 <b>Сумма:</b> <code>" + currentWithdrawal.getAmount() + "</code>\n"
                + "\uD83D\uDCB5 <b>Сумма в USD:</b> <code>" + currentWithdrawal.getPaymentAmount() + "</code>")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleSendTextMessage(sendAdminMessage);
    }

    private void handleCreateWithdrawalRefuse(long chatId, int messageId, long withdrawalId) {
        withdrawalRepository.deleteById(withdrawalId);

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("❌ <b>Вы успешно отменили заявку на выплату</b>")
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleAdminAcceptReplenishment(long chatId, int messageId, long replenishmentId, String callbackQueryId) {
        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text("Вы успешно приняли этот платеж")
                .build());

        handleDeleteMessage(DeleteMessage.builder()
                .chatId(chatId)
                .messageId(messageId)
                .build());

        Replenishment currentReplenishment = replenishmentRepository.findById(replenishmentId)
                .orElseThrow();

        Order currentOrder = orderRepository.findById(currentReplenishment.getOrderId())
                .orElseThrow();

        replenishmentRepository.updateStatusById(ReplenishmentStatus.ACCEPTED, replenishmentId);

        handleSendTextMessage(SendMessage.builder()
                .text("✅ <b>Ваша заявка на оплату заказа</b> <code>\"" + currentOrder.getTitle() + "\"</code> <b>в размере</b> <code>"
                        + currentReplenishment.getPaymentAmount() + "$</code> <b>была принята</b>")
                .chatId(userRepository.findTelegramIdById(currentOrder.getCustomerUserId()))
                .parseMode("html")
                .build());

        handleSendTextMessage(SendMessage.builder()
                .text("✅ <b>Заказчик оплатил принятый вами заказ</b> <code>\"" + currentOrder.getTitle() + "\"</code> <b>на сумму</b> <code>" + currentReplenishment.getPaymentAmount() + "$</code>")
                .chatId(userRepository.findTelegramIdById(currentOrder.getExecutorUserId()))
                .parseMode("html")
                .build());
    }


    private void handleAdminRefuseReplenishment(long chatId, int messageId, long replenishmentId, String callbackQueryId) {
        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text("Вы успешно отклонили этот платеж")
                .build());

        handleDeleteMessage(DeleteMessage.builder()
                .chatId(chatId)
                .messageId(messageId)
                .build());

        Replenishment currentReplenishment = replenishmentRepository.findById(replenishmentId)
                .orElseThrow();

        Order currentOrder = orderRepository.findById(currentReplenishment.getOrderId())
                .orElseThrow();

        replenishmentRepository.updateStatusById(ReplenishmentStatus.REFUSED, replenishmentId);

        handleSendTextMessage(SendMessage.builder()
                .text("❌ <b>Ваша недавняя заявка на оплату заказа</b> <code>\"" + currentOrder.getTitle() + "\"</code> <b>была отклонена</b>")
                .chatId(userRepository.findTelegramIdById(currentReplenishment.getUserId()))
                .parseMode("html")
                .build());
    }

    private void handleCreateReplenishmentRefuse(long chatId, int messageId, long replenishmentId) {
        Replenishment currentReplenishment = replenishmentRepository.findById(replenishmentId)
                        .orElseThrow();

        replenishmentRepository.delete(currentReplenishment);

        Order currentOrder = orderRepository.findById(currentReplenishment.getOrderId())
                .orElseThrow();

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("❌ <b>Вы успешно отменили платеж для заказа</b> <code>\"" + currentOrder.getTitle() + "\"</code>")
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleCreateWithdrawalMessage(long chatId) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        InlineKeyboardButton createWithdrawalButton = InlineKeyboardButton.builder()
                .callbackData(ExecutorCallback.CREATE_WITHDRAWAL_CALLBACK)
                .text("\uD83D\uDCB8 Вывести средства")
                .build();

        keyboardButtonsRow1.add(createWithdrawalButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage message = SendMessage.builder()
                .text("\uD83D\uDCB8 <b>Вывод средств</b>\n\n"
                + "Хотите вывести средства со своего баланса? Просто нажмите кнопку ниже, чтобы инициировать процесс вывода средств\n\n"
                + "\uD83D\uDCBC Удобство и безопасность в управлении вашими деньгами - наш приоритет\n\n"
                + "\uD83D\uDEE0\uFE0F <b>Не стесняйтесь обращаться в нашу поддержку, если у вас возникли вопросы или вам нужна помощь</b>")
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleSendTextMessage(message);
    }

    private void handleCreateAdminMessage(long chatId, User principalUser) {
        if (!principalUser.getRole().equals(UserRole.ADMIN)) {
            return;
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow5 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow6 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow7 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow8 = new ArrayList<>();

        InlineKeyboardButton allOrderButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GET_ALL_ORDER_CALLBACK + " 1")
                .text("\uD83D\uDECD Общий список заказов")
                .build();

        InlineKeyboardButton allInProgressOrderButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GET_ALL_IN_PROGRESS_ORDER_CALLBACK + " 1")
                .text("\uD83D\uDD34 Общий список заказов \"В разработке\"")
                .build();

        InlineKeyboardButton giveRoleButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GIVE_ROLE_CALLBACK)
                .text("➕ Выдать роль пользователю")
                .build();
        InlineKeyboardButton giveStatusButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GIVE_PREFIX_CALLBACK)
                .text("➕ Выдать префикс исполнителю")
                .build();

        InlineKeyboardButton addOrRemoveUserBalanceButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GIVE_BALANCE_CALLBACK)
                .text("➕ Выдать баланс пользователю")
                .build();

        InlineKeyboardButton createUserExecutorStatusButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_CREATE_EXECUTOR_PREFIX_CALLBACK)
                .text("➕ Добавить префикс исполнителя")
                .build();
        InlineKeyboardButton deleteUserExecutorStatusButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_DELETE_EXECUTOR_PREFIX_CALLBACK)
                .text("➖ Удалить префикс исполнителя")
                .build();

        InlineKeyboardButton getAllUserDataButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GET_ALL_USER_DATA_CALLBACK)
                .text("\uD83D\uDCD1 Получить данные всех пользователей")
                .build();

        InlineKeyboardButton getCurrentUserDataButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GET_CURRENT_USER_DATA_CALLBACK)
                .text("\uD83E\uDDFE Проверить пользователя")
                .build();

        InlineKeyboardButton banUserButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_BAN_USER_CALLBACK)
                .text("⬛\uFE0F Заблокировать пользователя")
                .build();
        InlineKeyboardButton unbanUserButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_UNBAN_USER_CALLBACK)
                .text("⬜\uFE0F Разблокировать пользователя")
                .build();

        keyboardButtonsRow1.add(allOrderButton);

        keyboardButtonsRow2.add(allInProgressOrderButton);

        keyboardButtonsRow3.add(giveRoleButton);
        keyboardButtonsRow3.add(giveStatusButton);

        keyboardButtonsRow4.add(addOrRemoveUserBalanceButton);

        keyboardButtonsRow5.add(createUserExecutorStatusButton);
        keyboardButtonsRow5.add(deleteUserExecutorStatusButton);

        keyboardButtonsRow6.add(getAllUserDataButton);

        keyboardButtonsRow7.add(getCurrentUserDataButton);

        keyboardButtonsRow8.add(banUserButton);
        keyboardButtonsRow8.add(unbanUserButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);
        rowList.add(keyboardButtonsRow4);
        rowList.add(keyboardButtonsRow5);
        rowList.add(keyboardButtonsRow6);
        rowList.add(keyboardButtonsRow7);
        rowList.add(keyboardButtonsRow8);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage adminMessage = SendMessage.builder()
                .chatId(chatId)
                .text("\uD83D\uDFE5 <b>Админ-панель</b>\n\n"
                + "<b>«С большой силой приходит большая ответственность»</b>")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleSendTextMessage(adminMessage);
    }

    private void handleEditAdminMessage(long chatId, int messageId, User principalUser) {
        if (!principalUser.getRole().equals(UserRole.ADMIN)) {
            return;
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow5 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow6 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow7 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow8 = new ArrayList<>();

        InlineKeyboardButton allOrderButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GET_ALL_ORDER_CALLBACK + " 1")
                .text("\uD83D\uDECD Общий список заказов")
                .build();

        InlineKeyboardButton allInProgressOrderButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GET_ALL_IN_PROGRESS_ORDER_CALLBACK + " 1")
                .text("\uD83D\uDD34 Общий список заказов \"В разработке\"")
                .build();

        InlineKeyboardButton giveRoleButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GIVE_ROLE_CALLBACK)
                .text("➕ Выдать роль пользователю")
                .build();
        InlineKeyboardButton giveStatusButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GIVE_PREFIX_CALLBACK)
                .text("➕ Выдать префикс исполнителю")
                .build();

        InlineKeyboardButton addOrRemoveUserBalanceButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GIVE_BALANCE_CALLBACK)
                .text("➕ Выдать баланс пользователю")
                .build();

        InlineKeyboardButton createUserExecutorStatusButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_CREATE_EXECUTOR_PREFIX_CALLBACK)
                .text("➕ Добавить префикс исполнителя")
                .build();
        InlineKeyboardButton deleteUserExecutorStatusButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_DELETE_EXECUTOR_PREFIX_CALLBACK)
                .text("➖ Удалить префикс исполнителя")
                .build();

        InlineKeyboardButton getAllUserDataButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GET_ALL_USER_DATA_CALLBACK)
                .text("\uD83D\uDCD1 Получить данные всех пользователей")
                .build();

        InlineKeyboardButton getCurrentUserDataButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GET_CURRENT_USER_DATA_CALLBACK)
                .text("\uD83E\uDDFE Проверить пользователя")
                .build();

        InlineKeyboardButton banUserButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_BAN_USER_CALLBACK)
                .text("⬛\uFE0F Заблокировать пользователя")
                .build();
        InlineKeyboardButton unbanUserButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_UNBAN_USER_CALLBACK)
                .text("⬜\uFE0F Разблокировать пользователя")
                .build();

        keyboardButtonsRow1.add(allOrderButton);

        keyboardButtonsRow2.add(allInProgressOrderButton);

        keyboardButtonsRow3.add(giveRoleButton);
        keyboardButtonsRow3.add(giveStatusButton);

        keyboardButtonsRow4.add(addOrRemoveUserBalanceButton);

        keyboardButtonsRow5.add(createUserExecutorStatusButton);
        keyboardButtonsRow5.add(deleteUserExecutorStatusButton);

        keyboardButtonsRow6.add(getAllUserDataButton);

        keyboardButtonsRow7.add(getCurrentUserDataButton);

        keyboardButtonsRow8.add(banUserButton);
        keyboardButtonsRow8.add(unbanUserButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);
        rowList.add(keyboardButtonsRow4);
        rowList.add(keyboardButtonsRow5);
        rowList.add(keyboardButtonsRow6);
        rowList.add(keyboardButtonsRow7);
        rowList.add(keyboardButtonsRow8);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editAdminMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("\uD83D\uDFE5 <b>Админ-панель</b>\n\n"
                        + "<b>«С большой силой приходит большая ответственность»</b>")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleEditMessageText(editAdminMessageText);
    }

    private void handleEditWithdrawalMessage(long chatId, int messageId) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        InlineKeyboardButton createWithdrawalButton = InlineKeyboardButton.builder()
                .callbackData(ExecutorCallback.CREATE_WITHDRAWAL_CALLBACK)
                .text("\uD83D\uDCB8 Вывести средства")
                .build();

        keyboardButtonsRow1.add(createWithdrawalButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .text("\uD83D\uDCB8 <b>Вывод средств</b>\n\n"
                        + "Хотите вывести средства со своего баланса? Просто нажмите кнопку ниже, чтобы инициировать процесс вывода средств\n\n"
                        + "\uD83D\uDCBC У нас вы можете легко и безопасно получить доступ к вашим деньгам\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Не стесняйтесь обращаться в нашу поддержку, если у вас возникли вопросы или вам нужна помощь</b>")
                .chatId(chatId)
                .messageId(messageId)
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleCreateWithdrawalProcess(long chatId, Integer messageId, String text, User principalUser, CreateWithdrawalObject createWithdrawalObject) {
        switch (createWithdrawalObject.getStep()) {
            case 1 -> {
                double amount = Double.parseDouble(text);

                if (amount > principalUser.getBalance()) {
                    handleSendTextMessage(SendMessage.builder()
                            .chatId(chatId)
                            .text("❌ <b>У вас не хватает баланса для подтверждения заявки на выплату</b>")
                            .parseMode("html")
                            .build());
                    return;
                }

                createWithdrawalObject.setStep(2);
                createWithdrawalObject.setAmount(amount);

                adminCreateWithdrawalSteps.put(principalUser.getId(), createWithdrawalObject);

                handleDeleteMessage(DeleteMessage.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .build());

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();

                InlineKeyboardButton createBtcWithdrawalButton = InlineKeyboardButton.builder()
                        .callbackData(ExecutorCallback.CREATE_WITHDRAWAL_SELECT_PAYMENT_METHOD_CALLBACK + " " + PaymentMethod.BTC)
                        .text("BTC")
                        .build();

                InlineKeyboardButton createLtcWithdrawalButton = InlineKeyboardButton.builder()
                        .callbackData(ExecutorCallback.CREATE_WITHDRAWAL_SELECT_PAYMENT_METHOD_CALLBACK + " " + PaymentMethod.LTC)
                        .text("LTC")
                        .build();

                InlineKeyboardButton createTrc20WithdrawalButton = InlineKeyboardButton.builder()
                        .callbackData(ExecutorCallback.CREATE_WITHDRAWAL_SELECT_PAYMENT_METHOD_CALLBACK + " " + PaymentMethod.TRC20)
                        .text("TRC20")
                        .build();

                InlineKeyboardButton backToMainCreateWithdrawalButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_CREATE_WITHDRAWAL_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                keyboardButtonsRow1.add(createBtcWithdrawalButton);
                keyboardButtonsRow2.add(createLtcWithdrawalButton);
                keyboardButtonsRow3.add(createTrc20WithdrawalButton);
                keyboardButtonsRow4.add(backToMainCreateWithdrawalButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);
                rowList.add(keyboardButtonsRow2);
                rowList.add(keyboardButtonsRow3);
                rowList.add(keyboardButtonsRow4);

                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .replyMarkup(inlineKeyboardMarkup)
                        .text("\uD83D\uDCB3 <b>Выберите метод выплаты</b>\n\n"
                        + "Пожалуйста, выберите удобный для вас метод выплаты из списка ниже\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Если у вас возникли вопросы или вам нужна помощь, не стесняйтесь обращаться в нашу поддержку</b>")
                        .parseMode("html")
                        .messageId(createWithdrawalObject.getBeginMessageId())
                        .build();

                handleEditMessageText(editMessageText);
            }

            case 3 -> {
                createWithdrawalObject.setStep(4);

                adminCreateWithdrawalSteps.put(principalUser.getId(), createWithdrawalObject);

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                InlineKeyboardButton backToMainCreateWithdrawalButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_CREATE_WITHDRAWAL_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                keyboardButtonsRow1.add(backToMainCreateWithdrawalButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);

                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(createWithdrawalObject.getBeginMessageId())
                        .text("\uD83C\uDF10 <b>Укажите адрес в крипто-сети</b>\n\n"
                        + "Теперь, пожалуйста, укажите ваш адрес в указанной ранее криптовалютной сети для получения средств\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Если у вас возникли вопросы или вам нужна помощь, не стесняйтесь обращаться в нашу поддержку</b>")
                        .replyMarkup(inlineKeyboardMarkup)
                        .parseMode("html")
                        .build();

                handleEditMessageText(editMessageText);
            }

            case 4 -> {
                adminCreateWithdrawalSteps.remove(principalUser.getId());

                handleDeleteMessage(DeleteMessage.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .build());

                Withdrawal newWithdrawal = Withdrawal.builder()
                        .status(WithdrawalStatus.IN_PROCESS)
                        .paymentMethod(createWithdrawalObject.getPaymentMethod())
                        .paymentAmount(createWithdrawalObject.getAmount())
                        .address(text)
                        .userId(principalUser.getId())
                        .createdAt(System.currentTimeMillis())
                        .build();

                BigDecimal withdrawalAmount;

                switch (newWithdrawal.getPaymentMethod()) {
                    case BTC -> {
                        withdrawalAmount = BigDecimal.valueOf(createWithdrawalObject.getAmount() / cryptoCurrency.getUsdPrice().get(CryptoToken.BTC));

                        withdrawalAmount = withdrawalAmount.setScale(7, RoundingMode.HALF_UP);

                        newWithdrawal.setAmount(withdrawalAmount.toPlainString());
                    }
                    case LTC -> {
                        withdrawalAmount = BigDecimal.valueOf(createWithdrawalObject.getAmount() / cryptoCurrency.getUsdPrice().get(CryptoToken.LTC));

                        withdrawalAmount = withdrawalAmount.setScale(4, RoundingMode.HALF_UP);

                        newWithdrawal.setAmount(withdrawalAmount.toPlainString());
                    }
                    case TRC20 -> {
                        withdrawalAmount = BigDecimal.valueOf(createWithdrawalObject.getAmount() / cryptoCurrency.getUsdPrice().get(CryptoToken.USDT));

                        withdrawalAmount = withdrawalAmount.setScale(2, RoundingMode.HALF_UP);

                        newWithdrawal.setAmount(withdrawalAmount.toPlainString());
                    }
                }

                newWithdrawal = withdrawalRepository.save(newWithdrawal);

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                InlineKeyboardButton acceptWithdrawalButton = InlineKeyboardButton.builder()
                        .callbackData(ExecutorCallback.CREATE_WITHDRAWAL_ACCEPT_CALLBACK + " " + newWithdrawal.getId())
                        .text("✅ Подтвердить")
                        .build();

                InlineKeyboardButton refuseWithdrawalButton = InlineKeyboardButton.builder()
                        .callbackData(ExecutorCallback.CREATE_WITHDRAWAL_REFUSE_CALLBACK + " " + newWithdrawal.getId())
                        .text("❌ Отменить")
                        .build();

                keyboardButtonsRow1.add(acceptWithdrawalButton);
                keyboardButtonsRow1.add(refuseWithdrawalButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);

                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(createWithdrawalObject.getBeginMessageId())
                        .text("\uD83D\uDCB3 <b>Данные для выплаты</b>\n\n"
                        + "Вы выбрали метод выплаты: <b>" + newWithdrawal.getPaymentMethod() + "</b>\n\n"
                        + "\uD83D\uDD39 <b>Адрес получателя:</b> <code>" + newWithdrawal.getAddress() + "</code>\n"
                        + "\uD83D\uDCB0 <b>Сумма в " + newWithdrawal.getPaymentMethod() + ":</b> <code>" + newWithdrawal.getAmount() + "</code>\n"
                        + "\uD83D\uDCB5 <b>Сумма в USD:</b> <code>" + newWithdrawal.getPaymentAmount() + "</code>\n\n"
                        + "\uD83D\uDD10 Пожалуйста, убедитесь, что все данные указанные вами верные\n\n"
                        + "\uD83D\uDEE0\uFE0F <b>Если у вас возникли вопросы или вам нужна помощь, не стесняйтесь обращаться в нашу поддержку</b>")
                        .replyMarkup(inlineKeyboardMarkup)
                        .parseMode("html")
                        .build();

                handleEditMessageText(editMessageText);
            }
        }
    }

    private void handleCreateWithdrawalSelectPaymentMethod(long chatId, PaymentMethod paymentMethod, User principalUser) {
        CreateWithdrawalObject createWithdrawalObject = adminCreateWithdrawalSteps.get(principalUser.getId());

        createWithdrawalObject.setStep(3);
        createWithdrawalObject.setPaymentMethod(paymentMethod);

        adminCreateWithdrawalSteps.put(principalUser.getId(), createWithdrawalObject);

        handleCreateWithdrawalProcess(chatId, null, null, principalUser, createWithdrawalObject);
    }

    private void handleAdminAcceptWithdrawal(long chatId, int messageId, long withdrawalId, String callbackQueryId) {
        Withdrawal currentWithdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow();

        withdrawalRepository.updateStatusById(WithdrawalStatus.ACCEPTED, withdrawalId);

        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text("Вы успешно приняли заявку на вывод")
                .build());

        handleDeleteMessage(DeleteMessage.builder()
                .chatId(chatId)
                .messageId(messageId)
                .build());

        handleSendTextMessage(SendMessage.builder()
                .chatId(userRepository.findTelegramIdById(currentWithdrawal.getUserId()))
                .text("✅ <b>Ваша заявка на вывод была успешно принята. Средства будут отправлены на ваш криптоадрес в ближайшее время</b>")
                .parseMode("html")
                .build());
    }

    private void handleAdminRefuseWithdrawal(long chatId, int messageId, long withdrawalId, String callbackQueryId) {
        Withdrawal currentWithdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow();

        User currentUser = userRepository.findById(currentWithdrawal.getUserId()).orElseThrow();

        userRepository.updateBalanceById(currentUser.getBalance() + currentWithdrawal.getPaymentAmount(),
                currentUser.getId());

        currentWithdrawal.setStatus(WithdrawalStatus.REFUSED);

        withdrawalRepository.save(currentWithdrawal);

        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text("Вы успешно отклонили заявку на вывод")
                .build());

        handleDeleteMessage(DeleteMessage.builder()
                .chatId(chatId)
                .messageId(messageId)
                .build());

        handleSendTextMessage(SendMessage.builder()
                .chatId(currentUser.getTelegramId())
                .text("❌ <b>Ваша недавняя заявка на вывод была отклонена</b>")
                .parseMode("html")
                .build());
    }

    private void handleAdminGetAllOrder(long chatId, int messageId, int page) {
        List<Order> principalOrders = orderRepository
                .findAllWithOffsetOrderByCreatedAtDesc(6, 6 * (page - 1));

        long principalOrdersCount = orderRepository.count();

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
                    .callbackData(AdminCallback.ADMIN_GET_CURRENT_ORDER_CALLBACK + " " + order.getId())
                    .text(order.getTitle() + " / " + (order.getBudget() == null ? "Договорный" : order.getBudget() + "$")
                            + " / " + orderStatusString)
                    .build();

            orderKeyboardButtonsRow.add(orderButton);

            rowList.add(orderKeyboardButtonsRow);
        }

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        if (page != 1) {
            InlineKeyboardButton prevButton = InlineKeyboardButton.builder()
                    .callbackData(AdminCallback.ADMIN_GET_ALL_ORDER_CALLBACK + " " + (page - 1))
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
                    .callbackData(AdminCallback.ADMIN_GET_ALL_ORDER_CALLBACK + " " + (page + 1))
                    .text("⏩ Следующая")
                    .build();

            keyboardButtonsRow1.add(nextButton);
        }

        rowList.add(keyboardButtonsRow1);

        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        keyboardButtonsRow2.add(backToMainAdminButton);

        rowList.add(keyboardButtonsRow2);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("\uD83D\uDECD <b>Общий список заказов</b>")
                .parseMode("html")
                .replyMarkup(inlineKeyboardMarkup)
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleAdminGetAllInProgressOrder(long chatId, int messageId, int page) {
        List<Order> principalOrders = orderRepository
                .findAllByOrderStatusWithOffsetOrderByCreatedAtDesc(OrderStatus.IN_PROGRESS, 6, 6 * (page - 1));

        long principalOrdersCount = orderRepository.countByOrderStatus(OrderStatus.IN_PROGRESS);

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
                    .callbackData(AdminCallback.ADMIN_GET_CURRENT_ORDER_CALLBACK + " " + order.getId())
                    .text(order.getTitle() + " / " + (order.getBudget() == null ? "Договорный" : order.getBudget() + "$")
                            + " / " + orderStatusString)
                    .build();

            orderKeyboardButtonsRow.add(orderButton);

            rowList.add(orderKeyboardButtonsRow);
        }

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        if (page != 1) {
            InlineKeyboardButton prevButton = InlineKeyboardButton.builder()
                    .callbackData(AdminCallback.ADMIN_GET_ALL_IN_PROGRESS_ORDER_CALLBACK + " " + (page - 1))
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
                    .callbackData(AdminCallback.ADMIN_GET_ALL_IN_PROGRESS_ORDER_CALLBACK + " " + (page + 1))
                    .text("⏩ Следующая")
                    .build();

            keyboardButtonsRow1.add(nextButton);
        }

        rowList.add(keyboardButtonsRow1);

        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        keyboardButtonsRow2.add(backToMainAdminButton);

        rowList.add(keyboardButtonsRow2);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("\uD83D\uDD34 <b>Общий список заказов \"В разработке\"</b>")
                .parseMode("html")
                .replyMarkup(inlineKeyboardMarkup)
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleAdminGetCurrentOrder(long chatId, int messageId, long orderId, User principalUser) {
        Order currentOrder = orderRepository.findById(orderId)
                .orElseThrow();

        User customerOrderUser = userRepository.findById(currentOrder.getCustomerUserId())
                .orElseThrow();

        User executorOrderUser = null;

        if (currentOrder.getExecutorUserId() != null) {
            executorOrderUser = userRepository.findById(currentOrder.getExecutorUserId())
                    .orElseThrow();
        }

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
                .append("\uD83C\uDD94 <b>ID создателя заказа:</b> <code>").append(customerOrderUser.getTelegramId()).append("</code>\n");

        if (executorOrderUser != null) {
            getCurrentOrderTextMessage.append("\uD83C\uDD94 <b>ID исполнителя заказа:</b> <code>").append(executorOrderUser.getTelegramId()).append("</code>\n\n");
        } else {
            getCurrentOrderTextMessage.append("\n");
        }

        getCurrentOrderTextMessage.append("\uD83D\uDCCC <b>Статус:</b> <code>").append(currentOrderStatusString).append("</code>\n");

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

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow5 = new ArrayList<>();

        //todo chat
        InlineKeyboardButton joinChatMessageButton = InlineKeyboardButton.builder()
                .callbackData(UserCallback.JOIN_TO_CHAT_CALLBACK + " " + principalUser.getId() + " " + orderId)
                .text("\uD83C\uDF00 Зайти в чат")
                .build();

        InlineKeyboardButton checkAllChatMessageButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_GET_ALL_CHAT_MESSAGE_CALLBACK + " " + orderId)
                .text("\uD83C\uDFA6 Просмотр всех сообщений чата")
                .build();

        InlineKeyboardButton changeOrderStatusButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_UPDATE_ORDER_STATUS_CALLBACK + " " + orderId)
                .text("\uD83D\uDCA0 Изменить статус заказа")
                .build();

        InlineKeyboardButton updateOrderButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_UPDATE_ORDER_CALLBACK + " " + currentOrder.getId())
                .text("\uD83D\uDD04 Обновить заказ")
                .build();
        InlineKeyboardButton destroyOrderButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_DESTROY_ORDER_CALLBACK + " " + currentOrder.getId())
                .text("\uD83D\uDCAE Уничтожить заказ")
                .build();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        keyboardButtonsRow1.add(joinChatMessageButton);

        keyboardButtonsRow2.add(checkAllChatMessageButton);

        keyboardButtonsRow3.add((changeOrderStatusButton));

        keyboardButtonsRow4.add(updateOrderButton);
        keyboardButtonsRow4.add(destroyOrderButton);

        keyboardButtonsRow5.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);
        rowList.add(keyboardButtonsRow4);
        rowList.add(keyboardButtonsRow5);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .replyMarkup(inlineKeyboardMarkup)
                .text(getCurrentOrderTextMessage.toString())
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleAdminDestroyOrder(long chatId, int messageId, String callbackQueryId, User principalUser, long orderId) {
        Order currentOrder = orderRepository.findById(orderId)
                .orElseThrow();

        User customerUserOrder = userRepository.findById(currentOrder.getCustomerUserId())
                        .orElseThrow();

        User executorUserOrder = null;

        if (userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()) != null
                && userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()).equals(currentOrder.getId())) {
            userIdOrderIdJoinedToChats.remove(currentOrder.getCustomerUserId());
        }

        if (currentOrder.getExecutorUserId() != null
                && userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()) != null
                && userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()).equals(currentOrder.getId())) {
            userIdOrderIdJoinedToChats.remove(currentOrder.getExecutorUserId());
        }

        if (currentOrder.getExecutorUserId() != null) {
            executorUserOrder = userRepository.findById(currentOrder.getExecutorUserId())
                    .orElseThrow();
        }

        messageRepository.deleteAllById(messageRepository.findAllIdByOrderId(currentOrder.getId()));
        replenishmentRepository.deleteAllById(replenishmentRepository.findAllIdByOrderId(currentOrder.getId()));
        orderRepository.delete(currentOrder);

        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text("Вы успешно уничтожилии заказ")
                .showAlert(false)
                .build());

        handleEditAdminMessage(chatId, messageId, principalUser);

        if (currentOrder.getTelegramChannelMessageId() != null) {
            handleDeleteMessage(DeleteMessage.builder()
                    .chatId(telegramBotProperty.getChannelChatId())
                    .messageId(currentOrder.getTelegramChannelMessageId())
                    .build());
        }

        handleSendTextMessage(SendMessage.builder()
                .chatId(userRepository.findTelegramIdById(currentOrder.getCustomerUserId()))
                .text("\uD83D\uDCAE <b>Заказ</b> <code>\"" + currentOrder.getTitle() + "\"</code> <b>был удален администратором</b>")
                .replyMarkup(getDefaultReplyKeyboardMarkup(customerUserOrder))
                .parseMode("html")
                .build());

        if (executorUserOrder != null) {
            handleSendTextMessage(SendMessage.builder()
                    .chatId(userRepository.findTelegramIdById(currentOrder.getExecutorUserId()))
                    .text("\uD83D\uDCAE <b>Заказ</b> <code>\"" + currentOrder.getTitle() + "\"</code> <b>был удален администратором</b>")
                    .replyMarkup(getDefaultReplyKeyboardMarkup(executorUserOrder))
                    .parseMode("html")
                    .build());
        }
    }

    private void handleAdminUpdateOrder(long chatId, int messageId, String callbackQueryId, User principalUser, long orderId) {
        Order currentOrder = orderRepository.findById(orderId)
                .orElseThrow();

        User executorUserOrder = null;

        if (currentOrder.getExecutorUserId() != null) {
            executorUserOrder = userRepository.findById(currentOrder.getExecutorUserId())
                    .orElseThrow();
        }

        if (currentOrder.getTelegramChannelMessageId() != null) {
            handleDeleteMessage(DeleteMessage.builder()
                    .chatId(telegramBotProperty.getChannelChatId())
                    .messageId(currentOrder.getTelegramChannelMessageId())
                    .build());
        }

        List<String> prefixes = null;

        try {
            prefixes = objectMapper.readValue(currentOrder.getPrefixes(), List.class);
        } catch (JsonProcessingException e) {
            log.warn(e.getMessage());
        }

        int telegramChannelId = generateOrderTextAndSend(currentOrder.getId(), currentOrder.getTitle(), currentOrder.getDescription(),
                currentOrder.getBudget(), prefixes);

        currentOrder.setOrderStatus(OrderStatus.ACTIVE);
        currentOrder.setExecutorUserId(null);
        currentOrder.setTelegramChannelMessageId(telegramChannelId);

        orderRepository.save(currentOrder);

        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text("Вы успешно обновили заказ")
                .showAlert(false)
                .build());

        handleEditAdminMessage(chatId, messageId, principalUser);

        if (currentOrder.getExecutorUserId() != null) {
            handleSendTextMessage(SendMessage.builder()
                    .chatId(executorUserOrder.getTelegramId())
                    .text("\uD83D\uDD04 <b>Заказ был обновлен. Администратор исключил вас из заказа</b>")
                    .replyMarkup(getDefaultReplyKeyboardMarkup(executorUserOrder))
                    .parseMode("html")
                    .build());
        }
    }

    private void handleAdminUpdateOrderStatus(long chatId, int messageId, long orderId) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();

        InlineKeyboardButton setStatusActiveButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_SELECT_ORDER_STATUS_CALLBACK + " " + orderId + " " + OrderStatus.ACTIVE)
                .text("Активный")
                .build();

        InlineKeyboardButton setStatusInProgressButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_SELECT_ORDER_STATUS_CALLBACK + " " + orderId + " " + OrderStatus.IN_PROGRESS)
                .text("В разработке")
                .build();

        InlineKeyboardButton setStatusCompletedButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ADMIN_SELECT_ORDER_STATUS_CALLBACK + " " + orderId + " " + OrderStatus.COMPLETED)
                .text("Завершен")
                .build();

        InlineKeyboardButton backToAdminGetCurrentOrderButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_ADMIN_GET_CURRENT_ORDER_CALLBACK + " " + orderId)
                .text("↩\uFE0F Назад")
                .build();

        keyboardButtonsRow1.add(setStatusActiveButton);
        keyboardButtonsRow2.add(setStatusInProgressButton);
        keyboardButtonsRow3.add(setStatusCompletedButton);
        keyboardButtonsRow4.add(backToAdminGetCurrentOrderButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);
        rowList.add(keyboardButtonsRow4);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("\uD83D\uDCA0 <b>Изменить статус заказа</b>\n\n"
                + "Выберите статус, который хотите установить заказу")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleAdminSetOrderStatus(long chatId, int messageId, String callbackQueryId, long orderId, OrderStatus orderStatus,
                                           User principalUser) {
        Order currentOrder = orderRepository.findById(orderId)
                        .orElseThrow();

        currentOrder.setOrderStatus(orderStatus);

        if (orderStatus.equals(OrderStatus.ACTIVE)) {
            currentOrder.setExecutorUserId(null);
        }

        orderRepository.save(currentOrder);

        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text("Вы успешно изменили статус этому заказу")
                .showAlert(false)
                .build());

        handleAdminGetCurrentOrder(chatId, messageId, orderId, principalUser);

        String orderStatusString = null;

        switch (orderStatus) {
            case ACTIVE -> orderStatusString = "Активный";
            case IN_PROGRESS -> orderStatusString = "В разработке";
            case COMPLETED -> orderStatusString = "Завершен";
        }

        handleSendTextMessage(SendMessage.builder()
                .chatId(userRepository.findTelegramIdById(currentOrder.getCustomerUserId()))
                .text("\uD83D\uDCA0 <b>Администратор изменил статус заказа</b> <code>\"" + currentOrder.getTitle() + "\"</code> <b>на</b> <code>\"" + orderStatusString + "\"</code>")
                .parseMode("html")
                .build());

        if (currentOrder.getExecutorUserId() != null) {
            handleSendTextMessage(SendMessage.builder()
                    .chatId(userRepository.findTelegramIdById(currentOrder.getExecutorUserId()))
                    .text("\uD83D\uDCA0 <b>Администратор изменил статус заказа</b> <code>\"" + currentOrder.getTitle() + "\"</code> <b>на</b> <code>\"" + orderStatusString + "\"</code>")
                    .parseMode("html")
                    .build());
        }
    }

    private void handleAdminGiveRole(long chatId, int messageId, User principalUser) {
        adminGiveRoleSteps.put(principalUser.getId(), GiveRoleObject.builder().step(1).beginMessageId(messageId).build());

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("➕ <b>Выдать роль пользователю</b>\n\n"
                + "Укажите TELEGRAM-ID пользователя, которому вы хотите выдать роль")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminGivePrefix(long chatId, int messageId, User principalUser) {
        adminGivePrefixSteps.put(principalUser.getId(), GivePrefixObject.builder().step(1).beginMessageId(messageId).build());

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("➕ <b>Выдать префикс исполнителю</b>\n\n"
                        + "Укажите TELEGRAM-ID исполнителя, которому вы хотите выдать префикс")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminGiveBalance(long chatId, int messageId, User principalUser) {
        adminGiveBalanceSteps.put(principalUser.getId(), GiveBalanceObject.builder()
                .step(1)
                .beginMessageId(messageId)
                .build());

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("➕ <b>Выдать баланс пользователю</b>\n\n"
                + "Укажите TELEGRAM-ID исполнителя, которому вы хотите выдать баланс")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        handleEditMessageText(editMessageText);
    }

    private void handleAdminGiveRoleProcess(long chatId, Integer messageId, String text, String callbackQueryId, User principalUser, GiveRoleObject giveRoleObject) {
        switch (giveRoleObject.getStep()) {
            case 1 -> {
                handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

                giveRoleObject.setStep(2);
                giveRoleObject.setTelegramUserId(Long.parseLong(text));

                adminGiveRoleSteps.put(principalUser.getId(), giveRoleObject);

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                InlineKeyboardButton giveCustomerRoleButton = InlineKeyboardButton.builder()
                        .callbackData(AdminCallback.ADMIN_SELECT_ROLE_CALLBACK + " " + UserRole.CUSTOMER)
                        .text("Заказчик")
                        .build();

                InlineKeyboardButton giveExecutorRoleButton = InlineKeyboardButton.builder()
                        .callbackData(AdminCallback.ADMIN_SELECT_ROLE_CALLBACK + " " + UserRole.EXECUTOR)
                        .text("Исполнитель")
                        .build();

                InlineKeyboardButton giveAdminRoleButton = InlineKeyboardButton.builder()
                        .callbackData(AdminCallback.ADMIN_SELECT_ROLE_CALLBACK + " " + UserRole.ADMIN)
                        .text("Администратор")
                        .build();

                InlineKeyboardButton backToAdminMainButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();

                keyboardButtonsRow1.add(giveCustomerRoleButton);
                keyboardButtonsRow2.add(giveExecutorRoleButton);
                keyboardButtonsRow3.add(giveAdminRoleButton);
                keyboardButtonsRow4.add(backToAdminMainButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);
                rowList.add(keyboardButtonsRow2);
                rowList.add(keyboardButtonsRow3);
                rowList.add(keyboardButtonsRow4);

                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(giveRoleObject.getBeginMessageId())
                        .text("➕ <b>Выдать роль пользователю</b>\n\n"
                        + "Выберите, какую роль вы хотите выдать пользователю")
                        .replyMarkup(inlineKeyboardMarkup)
                        .parseMode("html")
                        .build();

                handleEditMessageText(editMessageText);
            }

            case 3 -> {
                userRepository.updateRoleByTelegramId(giveRoleObject.getUserRole(), giveRoleObject.getTelegramUserId());

                handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                        .callbackQueryId(callbackQueryId)
                        .text("Вы успешно обновили роль пользователя")
                        .showAlert(false)
                        .build());

                adminGiveRoleSteps.remove(principalUser.getId());

                handleEditAdminMessage(chatId, giveRoleObject.getBeginMessageId(), principalUser);
            }
        }
    }

    private void handleAdminGivePrefixProcess(long chatId, Integer messageId, String text, String callbackQueryId,
                                              User principalUser, GivePrefixObject givePrefixObject) {
        switch (givePrefixObject.getStep()) {
            case 1 -> {
                givePrefixObject.setTelegramUserId(Long.parseLong(text));
                givePrefixObject.setStep(2);

                adminGivePrefixSteps.put(principalUser.getId(), givePrefixObject);

                Iterable<Prefix> prefixList = prefixRepository.findAll();

                handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                keyboardButtonsRow1.add(backToMainAdminButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                for (Prefix prefix : prefixList) {
                    List<InlineKeyboardButton> keyboardButtonsRow = new ArrayList<>();

                    InlineKeyboardButton selectPrefixButton = InlineKeyboardButton.builder()
                            .callbackData(AdminCallback.ADMIN_SELECT_PREFIX_CALLBACK + " " + prefix.getId())
                            .text(prefix.getName())
                            .build();

                    keyboardButtonsRow.add(selectPrefixButton);

                    rowList.add(keyboardButtonsRow);
                }

                rowList.add(keyboardButtonsRow1);
                inlineKeyboardMarkup.setKeyboard(rowList);

                EditMessageText editMessageText = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(givePrefixObject.getBeginMessageId())
                        .text("➕ <b>Выдать префикс исполнителю</b>\n\n"
                        + "Выберите, какой префикс вы хотите выдать исполнителю")
                        .replyMarkup(inlineKeyboardMarkup)
                        .parseMode("html")
                        .build();

                handleEditMessageText(editMessageText);
            }

            case 3 -> {
                adminGivePrefixSteps.remove(principalUser.getId());

                User executorUser = userRepository.findByTelegramId(givePrefixObject.getTelegramUserId())
                        .orElseThrow();
                List<String> prefixesString = null;

                try {
                    prefixesString = objectMapper.readValue(executorUser.getExecutorPrefixes(), ArrayList.class);

                    prefixesString.add(prefixRepository.findById(givePrefixObject.getPrefixId()).orElseThrow().getName());

                    userRepository.updateExecutorPrefixesByTelegramId(objectMapper.writeValueAsString(prefixesString),
                            givePrefixObject.getTelegramUserId());
                } catch (JsonProcessingException e) {
                    log.warn(e.getMessage());
                }

                handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                        .callbackQueryId(callbackQueryId)
                        .text("Вы успешно обновили префикс у исполнителя")
                        .showAlert(false)
                        .build());

                handleEditAdminMessage(chatId, messageId, principalUser);
            }
        }
    }

    private void handleAdminGiveBalanceProcess(long chatId, Integer messageId, String text,
                                               User principalUser, GiveBalanceObject giveBalanceObject) {
        switch (giveBalanceObject.getStep()) {
            case 1 -> {
                giveBalanceObject.setStep(2);
                giveBalanceObject.setTelegramUserId(Long.parseLong(text));

                adminGiveBalanceSteps.put(principalUser.getId(), giveBalanceObject);

                handleDeleteMessage(DeleteMessage.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .build());

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                keyboardButtonsRow1.add(backToMainAdminButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);

                inlineKeyboardMarkup.setKeyboard(rowList);

                handleEditMessageText(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(giveBalanceObject.getBeginMessageId())
                        .text("➕ <b>Выдать баланс пользователю</b>\n\n"
                                + "Укажите сумму, которую вы хотите прибавить пользователю")
                        .replyMarkup(inlineKeyboardMarkup)
                        .parseMode("html")
                        .build());
            }

            case 2 -> {
                double amount = Double.parseDouble(text);

                adminGiveBalanceSteps.remove(principalUser.getId());

                User changeableUser = userRepository.findByTelegramId(giveBalanceObject.getTelegramUserId())
                        .orElseThrow();

                changeableUser.setBalance(changeableUser.getBalance() + amount);

                userRepository.save(changeableUser);

                handleDeleteMessage(DeleteMessage.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .build());

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                        .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                        .text("↩\uFE0F Назад")
                        .build();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                keyboardButtonsRow1.add(backToMainAdminButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);

                inlineKeyboardMarkup.setKeyboard(rowList);

                handleEditMessageText(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(giveBalanceObject.getBeginMessageId())
                        .text("✅ <b>Вы успешно выдали баланс пользователю</b>")
                        .replyMarkup(inlineKeyboardMarkup)
                        .parseMode("html")
                        .build());
            }
        }
    }

    private void handleAdminGetAllUserData(long chatId, int messageId, String callbackQueryId, User principalUser) {
        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .build());

        Integer loadMessageMessageId = null;

        try {
            loadMessageMessageId = execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("⏳ <b>Загрузка данных всех пользователей...</b>")
                    .parseMode("html")
                    .build()).getMessageId();
        } catch (TelegramApiException ignored) {
        }

        List<User> users = (ArrayList<User>) userRepository.findAll();
        File allDataTempFile = null;

        try {
            allDataTempFile = Files.createTempFile("allDataTempFile", ".txt").toFile();
        } catch (IOException e) {
            log.warn(e.getMessage());
        }

        try {
            FileWriter fileWriter = new FileWriter(allDataTempFile);

            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);

            for (User user : users) {
                LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(user.getRegisteredAt()), ZoneId.systemDefault());

                // Форматирование даты и времени в требуемый формат
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yy в HH:mm");
                String formattedDateTime = dateTime.format(formatter);

                bufferedWriter.write("_________________________________\n"
                + "ID: " + user.getId() + "\n"
                + "TELEGRAM-ID: " + user.getTelegramId() + "\n"
                + "Роль: " + user.getRole() + "\n"
                + "Баланс: " + user.getBalance() + "$\n"
                + "Заблокирован: " + (user.getIsAccountNonLocked() ? "Нет" : "Да") + "\n"
                + "Дата регистрации: " + formattedDateTime);

                bufferedWriter.newLine();
            }


            bufferedWriter.close();
        } catch (IOException e) {
            log.warn(e.getMessage());
        }

        handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(loadMessageMessageId).build());

        handleSendDocumentMessage(SendDocument.builder()
                .chatId(chatId)
                .caption("✅ <b>Данные всех пользователей успешно получены</b>")
                .parseMode("html")
                .document(new InputFile(allDataTempFile))
                .build());

        allDataTempFile.delete();
    }

    private void handleAdminGetCurrentUserData(long chatId, int messageId, User principalUser) {
        adminGetCurrentUserDataBeginMessageIdSteps.put(principalUser.getId(), messageId);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("\uD83E\uDDFE <b>Проверить пользователя</b>\n\n"
                + "Укажите TELEGRAM-ID пользователя, которого вы хотите провверить")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminGetCurrentUserDataProcess(long chatId, int messageId, long telegramId, int beginMessageId, User principalUser) {
        adminGetCurrentUserDataBeginMessageIdSteps.remove(principalUser.getId());

        handleDeleteMessage(DeleteMessage.builder()
                .chatId(chatId)
                .messageId(messageId)
                .build());

        User currentUser = userRepository.findByTelegramId(telegramId)
                .orElse(null);

        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(principalUser.getRegisteredAt()), ZoneId.systemDefault());

        // Форматирование даты и времени в требуемый формат
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yy в HH:mm");
        String formattedDateTime = dateTime.format(formatter);

        String currentUserRole = null;

        // Выбираем текстовую роль для юзера
        switch (currentUser.getRole()) {
            case CUSTOMER -> currentUserRole = "Заказчик";
            case EXECUTOR -> currentUserRole = "Исполнитель";
            case ADMIN -> currentUserRole = "Администратор";
        }

        long currentUserOrdersCount = orderRepository.countByCustomerUserIdOrExecutorUserId(currentUser.getId(),
                principalUser.getId());

        String executorStatusString = "";

        if (currentUser.getRole().equals(UserRole.EXECUTOR)) {
            String[] currentUserPrefixes = null;
            try {
                currentUserPrefixes = objectMapper.readValue(currentUser.getExecutorPrefixes(), String[].class);
            } catch (JsonProcessingException e) {
                log.warn(e.getMessage());
            }

            if (currentUserPrefixes.length != 0) {
                executorStatusString = "ℹ\uFE0F <b>Префиксы исполнителя:</b> <code>" + String.join(", ", currentUserPrefixes) + "</code>\n\n";
            }
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(beginMessageId)
                .text("\uD83E\uDDFE <b>Проверить пользователя</b>\n\n"
                        + "\uD83D\uDD11 <b>ID:</b> <code>" + currentUser.getTelegramId() + "</code>\n"
                        + "\uD83C\uDFAD <b>Роль:</b> <code>" + currentUserRole + "</code>\n"
                        + executorStatusString
                        + "\uD83D\uDCB3 <b>Баланс:</b> <code>" + currentUser.getBalance() + "$</code>\n\n"
                        + "\uD83D\uDECD <b>Количество открытых заказов:</b> <code>" + currentUserOrdersCount + "</code>\n\n"
                        + "\uD83D\uDD52 <b>Дата регистрации:</b> <code>" + formattedDateTime + "</code>")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminCreateExecutorPrefix(long chatId, int messageId, User principalUser) {
        adminCreateExecutorPrefixBeginMessageIdSteps.put(principalUser.getId(), messageId);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("➕ <b>Добавить префикс исполнителя</b>\n\n"
                + "Укажите префикс исполнителя, который вы хотите добавить в систему")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminCreateExecutorPrefixProcess(long chatId, int messageId, String text,
                                                        User principalUser, int beginMessageId) {
        adminCreateExecutorPrefixBeginMessageIdSteps.remove(principalUser.getId());

        handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

        prefixRepository.save(Prefix.builder().name(text).build());

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(beginMessageId)
                .text("✅ <b>Вы успешно добавили в систему префикс</b> <code>\"" + text + "\"</code>")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminDeleteExecutorPrefix(long chatId, int messageId, User principalUser) {
        adminDeleteExecutorPrefixBeginMessageIdSteps.put(principalUser.getId(), messageId);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("➕ <b>Удалить префикс исполнителя</b>\n\n"
                        + "Укажите префикс исполнителя, который вы хотите удалить из системы")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminDeleteExecutorPrefixProcess(long chatId, int messageId, String text,
                                                 User principalUser, int beginMessageId) {
        adminDeleteExecutorPrefixBeginMessageIdSteps.remove(principalUser.getId());

        handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

        prefixRepository.deleteByName(text);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(beginMessageId)
                .text("✅ <b>Вы успешно удалили из системы префикс</b> <code>\"" + text + "\"</code>")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminBanUser(long chatId, int messageId, User principalUser) {
        adminBanUserBeginMessageIdSteps.put(principalUser.getId(), messageId);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("⬛\uFE0F <b>Заблокировать пользователя</b>\n\n"
                        + "Укажите TELEGRAM-ID пользователя, которого вы хотите заблокировать")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminUnbanUser(long chatId, int messageId, User principalUser) {
        adminUnbanUserBeginMessageIdSteps.put(principalUser.getId(), messageId);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("⬜\uFE0F <b>Разблокировать пользователя</b>\n\n"
                        + "Укажите TELEGRAM-ID пользователя, которого вы хотите разблокировать")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminBanUserProcess(long chatId, int messageId, String text,
                                           User principalUser, int beginMessageId) {
        long currentUserTelegramId = Long.parseLong(text);

        adminBanUserBeginMessageIdSteps.remove(principalUser.getId());

        handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

        userRepository.updateIsAccountNonLockedByTelegramId(false, currentUserTelegramId);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(beginMessageId)
                .text("✅ <b>Вы успешно заблокировали пользователя с TELEGRAM-ID с</b> <code>" + currentUserTelegramId + "</code>")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleAdminUnbanUserProcess(long chatId, int messageId, String text,
                                             User principalUser, int beginMessageId) {
        long currentUserTelegramId = Long.parseLong(text);

        adminUnbanUserBeginMessageIdSteps.remove(principalUser.getId());

        handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

        userRepository.updateIsAccountNonLockedByTelegramId(true, currentUserTelegramId);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backToMainAdminButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_TO_MAIN_ADMIN_CALLBACK)
                .text("↩\uFE0F Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backToMainAdminButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        handleEditMessageText(EditMessageText.builder()
                .chatId(chatId)
                .messageId(beginMessageId)
                .text("✅ <b>Вы успешно разблокировали пользователя с TELEGRAM-ID с</b> <code>" + currentUserTelegramId + "</code>")
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleUserJoinToChat(long chatId, String callbackQueryId, User principalUser, long orderId) {
        if (userIdOrderIdJoinedToChats.get(principalUser.getId()) != null) {
            handleSendTextMessage(SendMessage.builder()
                    .chatId(chatId)
                    .text("\uD83D\uDCAC <b>Вы успешно отключились от текущего чата</b>\n\n"
                    + "Теперь вы можете дальше пользоваться нашим ботом")
                    .replyMarkup(getDefaultReplyKeyboardMarkup(principalUser))
                    .parseMode("html")
                    .build());
        }

        userIdOrderIdJoinedToChats.put(principalUser.getId(), orderId);

        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .showAlert(false)
                .build());

        ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();
        replyKeyboardMarkup.setSelective(true);
        replyKeyboardMarkup.setResizeKeyboard(true);
        replyKeyboardMarkup.setOneTimeKeyboard(false);

        KeyboardButton leaveFromChatButton = new KeyboardButton("\uD83D\uDD34 Выйти из текущего чата");
        KeyboardButton callAdminButton = new KeyboardButton("\uD83E\uDD19 Позвать администратора");

        KeyboardRow keyboardRow1 = new KeyboardRow();
        KeyboardRow keyboardRow2 = new KeyboardRow();

        keyboardRow1.add(leaveFromChatButton);
        keyboardRow2.add(callAdminButton);

        List<KeyboardRow> keyboardRows = new ArrayList<>();

        keyboardRows.add(keyboardRow1);
        keyboardRows.add(keyboardRow2);

        replyKeyboardMarkup.setKeyboard(keyboardRows);

        handleSendTextMessage(SendMessage.builder()
                .chatId(chatId)
                .text("✅\uD83D\uDCAC <b>Вы успешно подключились к текущему чату</b>\n\n"
                + "Теперь, при отправке любого сообщения в наш бот оно будет анонимно передано вашему собеседнику")
                .replyMarkup(replyKeyboardMarkup)
                .parseMode("html")
                .build());

        if (principalUser.getRole().equals(UserRole.CUSTOMER)) {
            messageRepository.saveAll(
                    messageRepository.findAllByOrderIdAndIsCustomerSeen(orderId, false).stream()
                    .peek(m -> {
                        UserRole messagerSenderUserRole = userRepository.findRoleById(m.getSenderId());

                        String messageNotifyUser = null;

                        switch (messagerSenderUserRole) {
                            case CUSTOMER -> messageNotifyUser = "заказчика";
                            case EXECUTOR -> messageNotifyUser = "исполнителя";
                            case ADMIN -> messageNotifyUser = "администратора";
                        }

                        handleSendTextMessage(SendMessage.builder()
                                .chatId(principalUser.getTelegramId())
                                .text("\uD83D\uDCAC❗\uFE0F <b>Вы пропустили сообщение от " + messageNotifyUser + " с текстом:</b>\n\n"
                                + "<i>" + m.getText() + "</i>")
                                .replyMarkup(replyKeyboardMarkup)
                                .parseMode("html")
                                .build());
                        m.setIsCustomerSeen(true);
                    }).toList()
            );
        } else if (principalUser.getRole().equals(UserRole.EXECUTOR)) {
            messageRepository.saveAll(
                    messageRepository.findAllByOrderIdAndIsExecutorSeen(orderId, false).stream()
                            .peek(m -> {
                                UserRole messagerSenderUserRole = userRepository.findRoleById(m.getSenderId());

                                String messageNotifyUser = null;

                                switch (messagerSenderUserRole) {
                                    case CUSTOMER -> messageNotifyUser = "заказчика";
                                    case EXECUTOR -> messageNotifyUser = "исполнителя";
                                    case ADMIN -> messageNotifyUser = "администратора";
                                }

                                handleSendTextMessage(SendMessage.builder()
                                        .chatId(principalUser.getTelegramId())
                                        .text("\uD83D\uDCAC❗\uFE0F <b>Вы пропустили сообщение от " + messageNotifyUser + " с текстом:</b>\n\n"
                                                + "<i>" + m.getText() + "</i>")
                                        .replyMarkup(replyKeyboardMarkup)
                                        .parseMode("html")
                                        .build());

                                m.setIsExecutorSeen(true);
                            }).toList()
            );
        }
    }

    private void handleUserSendMessageToChat(long chatId, int messageId, String text, User principalUser, long orderId) {
        handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

        Order currentOrder = orderRepository.findById(orderId)
                .orElseThrow();

        ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();
        replyKeyboardMarkup.setSelective(true);
        replyKeyboardMarkup.setResizeKeyboard(true);
        replyKeyboardMarkup.setOneTimeKeyboard(false);

        KeyboardButton leaveFromChatButton = new KeyboardButton("\uD83D\uDD34 Выйти из текущего чата");
        KeyboardButton callAdminButton = new KeyboardButton("\uD83E\uDD19 Позвать администратора");

        KeyboardRow keyboardRow1 = new KeyboardRow();
        KeyboardRow keyboardRow2 = new KeyboardRow();

        keyboardRow1.add(leaveFromChatButton);
        keyboardRow2.add(callAdminButton);

        List<KeyboardRow> keyboardRows = new ArrayList<>();

        keyboardRows.add(keyboardRow1);
        keyboardRows.add(keyboardRow2);

        replyKeyboardMarkup.setKeyboard(keyboardRows);

        if (currentOrder.getExecutorUserId() == null) {
            handleSendTextMessage(SendMessage.builder()
                    .chatId(chatId)
                    .text("❌\uD83D\uDCAC <b>Вы не можете отправить сообщение в чат, так как вы не имеете активного собеседника (исполнителя)</b>")
                    .replyMarkup(replyKeyboardMarkup)
                    .parseMode("html")
                    .build());

            return;
        }

        Message newMessage = Message.builder()
                .senderId(principalUser.getId())
                .orderId(orderId)
                .text(text)
                .build();

        if (currentOrder.getCustomerUserId().equals(principalUser.getId())) {
            if (userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()) != null &&
                    userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()).equals(currentOrder.getId())) {
                newMessage.setIsCustomerSeen(true);
                newMessage.setIsExecutorSeen(true);

                handleSendTextMessage(SendMessage.builder()
                        .chatId(userRepository.findTelegramIdById(currentOrder.getExecutorUserId()))
                        .text("✅\uD83D\uDC41\u200D\uD83D\uDDE8 <b>Вы получили сообщение от заказчика с текстом:</b>\n\n"
                                + "<i>" + text + "</i>")
                        .replyMarkup(replyKeyboardMarkup)
                        .parseMode("html")
                        .build());
            } else {
                newMessage.setIsCustomerSeen(true);
                newMessage.setIsExecutorSeen(false);
            }

            userRepository.findAllIdByRole(UserRole.ADMIN).stream()
                    .filter(adminId -> userIdOrderIdJoinedToChats.get(adminId) != null &&
                            userIdOrderIdJoinedToChats.get(adminId).equals(orderId))
                    .forEach(adminId -> {
                        handleSendTextMessage(SendMessage.builder()
                                .chatId(userRepository.findTelegramIdById(adminId))
                                .text("✅\uD83D\uDC41\u200D\uD83D\uDDE8 <b>Вы получили сообщение от заказчика с текстом:</b>\n\n"
                                        + "<i>" + text + "</i>")
                                .replyMarkup(replyKeyboardMarkup)
                                .parseMode("html")
                                .build());
                    });

        } else if (currentOrder.getExecutorUserId().equals(principalUser.getId())) {
            if (userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()) != null &&
                    userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()).equals(currentOrder.getId())) {
                newMessage.setIsExecutorSeen(true);
                newMessage.setIsCustomerSeen(true);

                handleSendTextMessage(SendMessage.builder()
                        .chatId(userRepository.findTelegramIdById(currentOrder.getCustomerUserId()))
                        .text("✅\uD83D\uDC41\u200D\uD83D\uDDE8 <b>Вы получили сообщение от исполнителя с текстом:</b>\n\n"
                                + "<i>" + text + "</i>")
                        .replyMarkup(replyKeyboardMarkup)
                        .parseMode("html")
                        .build());
            } else {
                newMessage.setIsExecutorSeen(true);
                newMessage.setIsCustomerSeen(false);
            }

            userRepository.findAllIdByRole(UserRole.ADMIN).stream()
                    .filter(adminId -> userIdOrderIdJoinedToChats.get(adminId) != null &&
                            userIdOrderIdJoinedToChats.get(adminId).equals(orderId))
                    .forEach(adminId -> {
                        handleSendTextMessage(SendMessage.builder()
                                .chatId(userRepository.findTelegramIdById(adminId))
                                .text("✅\uD83D\uDC41\u200D\uD83D\uDDE8 <b>Вы получили сообщение от исполнителя с текстом:</b>\n\n"
                                        + "<i>" + text + "</i>")
                                .replyMarkup(replyKeyboardMarkup)
                                .parseMode("html")
                                .build());
                    });
        } else if (principalUser.getRole().equals(UserRole.ADMIN)){
            if (userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()) != null &&
                    userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()).equals(currentOrder.getId()) &&
                    userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()) != null &&
                    userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()).equals(currentOrder.getId())) {
                newMessage.setIsExecutorSeen(true);
                newMessage.setIsCustomerSeen(true);

                handleSendTextMessage(SendMessage.builder()
                        .chatId(userRepository.findTelegramIdById(currentOrder.getCustomerUserId()))
                        .text("✅\uD83D\uDC41\u200D\uD83D\uDDE8 <b>Вы получили сообщение от администратора с текстом:</b>\n\n"
                                + "<i>" + text + "</i>")
                        .replyMarkup(replyKeyboardMarkup)
                        .parseMode("html")
                        .build());

                handleSendTextMessage(SendMessage.builder()
                        .chatId(userRepository.findTelegramIdById(currentOrder.getExecutorUserId()))
                        .text("✅\uD83D\uDC41\u200D\uD83D\uDDE8 <b>Вы получили сообщение от администратора с текстом:</b>\n\n"
                                    + "<i>" + text + "</i>")
                        .replyMarkup(replyKeyboardMarkup)
                        .parseMode("html")
                        .build());
            } else if (userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()) != null &&
                    userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()).equals(currentOrder.getId()) &&
                    userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()) == null) {
                newMessage.setIsCustomerSeen(true);
                newMessage.setIsExecutorSeen(false);

                handleSendTextMessage(SendMessage.builder()
                        .chatId(userRepository.findTelegramIdById(currentOrder.getCustomerUserId()))
                        .text("✅\uD83D\uDC41\u200D\uD83D\uDDE8 <b>Вы получили сообщение от администратора с текстом:</b>\n\n"
                                + "<i>" + text + "</i>")
                        .replyMarkup(replyKeyboardMarkup)
                        .parseMode("html")
                        .build());
            } else if (userIdOrderIdJoinedToChats.get(currentOrder.getCustomerUserId()) == null &&
                    userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()) != null &&
                    userIdOrderIdJoinedToChats.get(currentOrder.getExecutorUserId()).equals(currentOrder.getId())) {
                newMessage.setIsCustomerSeen(false);
                newMessage.setIsExecutorSeen(true);

                handleSendTextMessage(SendMessage.builder()
                        .chatId(userRepository.findTelegramIdById(currentOrder.getExecutorUserId()))
                        .text("✅\uD83D\uDC41\u200D\uD83D\uDDE8 <b>Вы получили сообщение от администратора с текстом:</b>\n\n"
                                + "<i>" + text + "</i>")
                        .replyMarkup(replyKeyboardMarkup)
                        .parseMode("html")
                        .build());

            } else {
                newMessage.setIsExecutorSeen(false);
                newMessage.setIsCustomerSeen(false);
            }

            userRepository.findAllIdByRole(UserRole.ADMIN).stream()
                    .filter(adminId -> !adminId.equals(principalUser.getId()) &&
                            userIdOrderIdJoinedToChats.get(adminId) != null &&
                            userIdOrderIdJoinedToChats.get(adminId).equals(orderId))
                    .forEach(adminId -> {
                        handleSendTextMessage(SendMessage.builder()
                                .chatId(userRepository.findTelegramIdById(adminId))
                                .text("✅\uD83D\uDC41\u200D\uD83D\uDDE8 <b>Вы получили сообщение от администратора с текстом:</b>\n\n"
                                        + "<i>" + text + "</i>")
                                .replyMarkup(replyKeyboardMarkup)
                                .parseMode("html")
                                .build());
                    });
        }

        newMessage.setSentAt(System.currentTimeMillis());

        System.out.println(newMessage.toString());

        messageRepository.save(newMessage);

        handleSendTextMessage(SendMessage.builder()
                .chatId(chatId)
                .text("✅\uD83D\uDCE9 <b>Вы успешно отправили сообщение собеседнику, с текстом:</b>\n\n"
                        + "<i>" + text + "</i>")
                .replyMarkup(replyKeyboardMarkup)
                .parseMode("html")
                .build());
    }

    private void handleUserLeaveFromChat(long chatId, User principalUser) {
        userIdOrderIdJoinedToChats.remove(principalUser.getId());

        handleSendTextMessage(SendMessage.builder()
                .chatId(chatId)
                .text("\uD83D\uDCAC <b>Вы успешно отключились от текущего чата</b>\n\n"
                        + "Теперь вы можете дальше пользоваться нашим ботом")
                .replyMarkup(getDefaultReplyKeyboardMarkup(principalUser))
                .parseMode("html")
                .build());
    }

    private void handleUserCallAdmin(long chatId, int messageId, User principalUser) {
        if (userIdOrderIdJoinedToChats.get(principalUser.getId()) == null) {
            //silence
            return;
        }

        long currentOrderId = userIdOrderIdJoinedToChats.get(principalUser.getId());

        handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton openChatButton = InlineKeyboardButton.builder()
                .callbackData(UserCallback.JOIN_TO_CHAT_CALLBACK + " " + principalUser.getId() + " "
                        + currentOrderId)
                .text("\uD83D\uDCAC Открыть чат")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(openChatButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        userRepository.findAllTelegramIdByRole(UserRole.ADMIN).stream()
                .forEach(adminTelegramId -> {
                    handleSendTextMessage(SendMessage.builder()
                            .text("‼\uFE0F <b>Вас позвали, как администратора в чат по заказу с названием</b> <code>\""
                                    + orderRepository.findTitleById(currentOrderId) + "\"</code>")
                            .chatId(adminTelegramId)
                            .replyMarkup(inlineKeyboardMarkup)
                            .parseMode("html")
                            .build());
                });

        handleSendTextMessage(SendMessage.builder()
                .text("\uD83E\uDD19 <b>Вы успешно позвали администратора в чат</b>\n\n"
                + "Теперь, ожидайте. Администратор отпишет, как прибудет")
                .chatId(chatId)
                .parseMode("html")
                .build());
    }

    private void handleAdminGetAllChatMessage(long chatId, String callbackQueryId, User principalUser, long orderId) {
        handleAnswerCallbackQuery(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .build());

        Integer loadMessageMessageId = null;

        try {
            loadMessageMessageId = execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("⏳ <b>Загрузка данных всех сообщений чата пользователей...</b>")
                    .parseMode("html")
                    .build()).getMessageId();
        } catch (TelegramApiException ignored) {
        }

        List<Message> messages = (ArrayList<Message>) messageRepository.findAllByOrderId(orderId);
        File allDataTempFile = null;

        try {
            allDataTempFile = Files.createTempFile("allChatMessagesTempFile", ".txt").toFile();
        } catch (IOException e) {
            log.warn(e.getMessage());
        }

        try {
            FileWriter fileWriter = new FileWriter(allDataTempFile);

            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);

            for (Message message : messages) {
                User senderMessageUser = userRepository.findById(message.getSenderId())
                        .orElseThrow();

                String senderMessageUserRoleString = null;

                switch (senderMessageUser.getRole()) {
                    case CUSTOMER -> senderMessageUserRoleString = "Заказчик";
                    case EXECUTOR -> senderMessageUserRoleString = "Исполнитель";
                    case ADMIN -> senderMessageUserRoleString = "Администратор";
                }

                LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(message.getSentAt()), ZoneId.systemDefault());

                // Форматирование даты и времени в требуемый формат
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yy в HH:mm");
                String formattedDateTime = dateTime.format(formatter);

                bufferedWriter.write("_____________________________\n"
                + "TELEGRAM-ID Отправителя: " + senderMessageUser.getTelegramId() + "\n"
                + "Текст: " + message.getText() + "\n"
                + "Роль: " + senderMessageUserRoleString + "\n"
                + "Дата отправки: " + formattedDateTime);

                bufferedWriter.newLine();
            }

            bufferedWriter.close();
        } catch (IOException e) {
            log.warn(e.getMessage());
        }

        handleDeleteMessage(DeleteMessage.builder().chatId(chatId).messageId(loadMessageMessageId).build());

        handleSendDocumentMessage(SendDocument.builder()
                .chatId(chatId)
                .caption("✅ <b>Данные всех сообщений чата успешно получены</b>")
                .parseMode("html")
                .document(new InputFile(allDataTempFile))
                .build());

        allDataTempFile.delete();
    }

    //Sys methods

    private ReplyKeyboardMarkup getDefaultReplyKeyboardMarkup(User principalUser) {
        ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();
        replyKeyboardMarkup.setSelective(true);
        replyKeyboardMarkup.setResizeKeyboard(true);
        replyKeyboardMarkup.setOneTimeKeyboard(false);

        KeyboardButton profileButton = new KeyboardButton("\uD83D\uDC64 Профиль");
        KeyboardButton rulesButton = new KeyboardButton("\uD83D\uDCDB Правила использования");

        KeyboardRow keyboardRow1 = new KeyboardRow();
        KeyboardRow keyboardRow2 = new KeyboardRow();

        keyboardRow1.add(profileButton);
        keyboardRow2.add(rulesButton);

        List<KeyboardRow> keyboardRows = new ArrayList<>();

        keyboardRows.add(keyboardRow1);
        keyboardRows.add(keyboardRow2);

        switch (principalUser.getRole()) {
            case CUSTOMER -> {
                KeyboardRow keyboardRow3 = new KeyboardRow();

                KeyboardButton createOrderButton = new KeyboardButton("➕ Создать заказ");
                KeyboardButton myOrdersButton = new KeyboardButton("\uD83D\uDCCB Мои заказы");

                keyboardRow3.add(createOrderButton);
                keyboardRow3.add(myOrdersButton);

                KeyboardRow keyboardRow4 = new KeyboardRow();

                KeyboardButton createReplenishmentButton = new KeyboardButton("\uD83D\uDCB0 Пополнить заказ");

                keyboardRow4.add(createReplenishmentButton);

                keyboardRows.add(keyboardRow3);
                keyboardRows.add(keyboardRow4);
            }

            case EXECUTOR -> {
                KeyboardRow keyboardRow3 = new KeyboardRow();
                KeyboardRow keyboardRow4 = new KeyboardRow();

                KeyboardButton myOrdersButton = new KeyboardButton("\uD83D\uDCCB Мои заказы");
                KeyboardButton withdrawalButton = new KeyboardButton("\uD83D\uDCB8 Вывод средств");

                keyboardRow3.add(myOrdersButton);
                keyboardRow4.add(withdrawalButton);

                keyboardRows.add(keyboardRow3);
                keyboardRows.add(keyboardRow4);
            }

            case ADMIN -> {
                KeyboardRow keyboardRow3 = new KeyboardRow();

                KeyboardButton adminPanelButton = new KeyboardButton("\uD83D\uDFE5 Админ-панель");

                keyboardRow3.add(adminPanelButton);

                keyboardRows.add(keyboardRow3);
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

    private void handleSendDocumentMessage(SendDocument sendDocument) {
        try {
            execute(sendDocument);
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
