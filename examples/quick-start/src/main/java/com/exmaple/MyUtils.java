package com.exmaple;

import org.apache.commons.lang3.StringUtils;

public final class MyUtils {
    private MyUtils() {}

    public static String greet(String name) {
        if (StringUtils.isBlank(name)) {
            return "Hello, World!";
        } else {
            return "Hello, " + StringUtils.trim(name) + "!";
        }
    }
}
