package org.example.servicecommon.until;

import org.springframework.stereotype.Component;

@Component
public  class UserContext {
    private static final ThreadLocal<Long> currentUserId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentUserName = new ThreadLocal<>();
    private static final ThreadLocal<String> currentIp = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        currentUserId.set(userId);
    }
    public static void setCurrentIp(String ip) {
        currentIp.set(ip);
    }
    public static void setUserName(String userName) {currentUserName.set(userName);}

    public static Long getUserId() {return currentUserId.get();}
    public static String getIp() {return currentIp.get();}
    public static String getUserName() {return currentUserName.get();}
    public static void clear() {
        currentUserId.remove();
        currentUserName.remove();
        currentIp.remove();
    }
}

