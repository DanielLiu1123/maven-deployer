package exmaples.multimodules.module2;

import org.apache.commons.lang3.StringUtils;

public final class Module2Utils {
    private Module2Utils() {}

    public static String greet(String name) {
        if (StringUtils.isBlank(name)) {
            return "Hello, World!";
        } else {
            return "Hello, " + StringUtils.trim(name) + "!";
        }
    }
}
