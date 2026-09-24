package com.frauddetection.common;

import java.util.Arrays;
import java.util.Optional;

/** Merchants selectable in the simulator. The enum name is the code stored in {@link Transaction#merchant()}. */
public enum Merchant {
    SHOPEE("Shopee"),
    LAZADA("Lazada"),
    GRAB("Grab"),
    CIRCLE_K("Circle K"),
    HIGHLANDS("Highlands Coffee"),
    WINMART("WinMart"),
    TGDD("Thế Giới Di Động"),
    ATM("ATM rút tiền");

    private final String displayName;

    Merchant(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Case-insensitive lookup by code, e.g. "atm" -> ATM. */
    public static Optional<Merchant> fromCode(String code) {
        return Arrays.stream(values()).filter(m -> m.name().equalsIgnoreCase(code)).findFirst();
    }
}
