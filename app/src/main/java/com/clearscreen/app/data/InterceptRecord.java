package com.clearscreen.app.data;

public class InterceptRecord {
    private String packageName;
    private String appName;
    private int count;
    private long lastInterceptTime;

    public InterceptRecord(String packageName, String appName, int count, long lastInterceptTime) {
        this.packageName = packageName;
        this.appName = appName;
        this.count = count;
        this.lastInterceptTime = lastInterceptTime;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getAppName() {
        return appName;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public long getLastInterceptTime() {
        return lastInterceptTime;
    }

    public void setLastInterceptTime(long lastInterceptTime) {
        this.lastInterceptTime = lastInterceptTime;
    }
}
