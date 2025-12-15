package com.exmaple;

import org.apache.commons.lang3.StringUtils;

public final class QuickStartUtils {
    private QuickStartUtils() {}

    public static String greet(String name) {
        if (StringUtils.isBlank(name)) {
            return "Hello, World!";
        } else {
            return "Hello, " + StringUtils.trim(name) + "!";
        }
    }
}
