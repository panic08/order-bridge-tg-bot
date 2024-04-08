package ru.panic.orderbridgebot.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "prefixes_table")
@Data
@Builder
public class Prefix {
    @Id
    private Long id;
    private String name;
}
