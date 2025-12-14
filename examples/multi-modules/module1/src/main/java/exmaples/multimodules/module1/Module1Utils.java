package exmaples.multimodules.module1;

import org.apache.commons.lang3.StringUtils;

public final class Module1Utils {
    private Module1Utils() {}

    public static String greet(String name) {
        if (StringUtils.isBlank(name)) {
            return "Hello, World!";
        } else {
            return "Hello, " + StringUtils.trim(name) + "!";
        }
    }
}
