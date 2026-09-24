package com.frauddetection.common;

import java.util.Arrays;
import java.util.Optional;

/** Cities selectable in the simulator, so users never have to type lat/lon. */
public enum City {
    HA_NOI("Hà Nội", 21.0285, 105.8542),
    HO_CHI_MINH("TP.HCM", 10.8231, 106.6297),
    DA_NANG("Đà Nẵng", 16.0544, 108.2022),
    HAI_PHONG("Hải Phòng", 20.8449, 106.6881),
    CAN_THO("Cần Thơ", 10.0452, 105.7469),
    NHA_TRANG("Nha Trang", 12.2388, 109.1967),
    SINGAPORE("Singapore", 1.3521, 103.8198),
    BANGKOK("Bangkok", 13.7563, 100.5018),
    TOKYO("Tokyo", 35.6762, 139.6503);

    private final String displayName;
    private final Location location;

    City(String displayName, double lat, double lon) {
        this.displayName = displayName;
        this.location = new Location(lat, lon);
    }

    public String displayName() {
        return displayName;
    }

    public Location location() {
        return location;
    }

    /** Case-insensitive lookup by code, e.g. "ha_noi" -> HA_NOI. */
    public static Optional<City> fromCode(String code) {
        return Arrays.stream(values()).filter(c -> c.name().equalsIgnoreCase(code)).findFirst();
    }
}
