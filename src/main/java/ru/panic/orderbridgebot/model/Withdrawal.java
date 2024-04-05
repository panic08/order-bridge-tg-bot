package ru.panic.orderbridgebot.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import ru.panic.orderbridgebot.model.type.PaymentMethod;
import ru.panic.orderbridgebot.model.type.WithdrawalStatus;

@Table(name = "withdrawals_table")
@Data
@Builder
public class Withdrawal {
    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    private WithdrawalStatus status;

    @Column("payment_method")
    private PaymentMethod paymentMethod;

    private String amount;

    private String address;

    @Column("payment_amount")
    private Double paymentAmount;

    @Column("created_at")
    private Long createdAt;
}
