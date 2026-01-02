package com.example;

import org.apache.commons.lang3.StringUtils;

public class KotlinDSLUtil {
    private KotlinDSLUtil() {}

    public static String greet(String name) {
        if (StringUtils.isBlank(name)) {
            return "Hello, World!";
        } else {
            return "Hello, " + StringUtils.trim(name) + "!";
        }
    }
}
