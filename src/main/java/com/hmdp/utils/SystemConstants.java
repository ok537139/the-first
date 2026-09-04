package com.hmdp.utils;

import java.io.File;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = System.getProperty("user.dir")
            + File.separator + "uploads" + File.separator;
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
