package ru.panic.orderbridgebot.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "prefixes_table")
@Data
public class Prefix {
    @Id
    private Long id;
    private String name;
}
