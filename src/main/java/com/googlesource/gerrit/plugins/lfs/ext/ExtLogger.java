package com.googlesource.gerrit.plugins.lfs;

import java.io.IOException;
import java.util.logging.*;

public class ExtLogger {
    private static final Logger logger = Logger.getLogger(ExtLogger.class.getName());
    private static boolean initialized = false;
    private static final String defaultTAG = " [++ Sampee ++] ";
    // ✅ 用 ThreadLocal 传递 caller 信息（线程安全）
    private static final ThreadLocal<String> callerInfo = new ThreadLocal<>();

    public enum LogMode {
        CONSOLE_ONLY,
        FILE_ONLY,
        BOTH
    }

    private static synchronized void initialize(LogMode mode, String logFilePath) {
        if (initialized) return;

        for (Handler h : logger.getHandlers()) {
            h.close();
            logger.removeHandler(h);
        }

        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                // 从 ThreadLocal 获取 caller（由 logWithLevel 设置）
                String caller = callerInfo.get();
                if (caller == null) caller = "unknown";

                return String.format("[%1$tF %1$tT]" + defaultTAG + "[%2$s] [%3$s] %4$s%n",
                    record.getMillis(),
                    record.getLevel().getName(),
                    caller,
                    record.getMessage());
            }
        };

        if (mode == LogMode.CONSOLE_ONLY || mode == LogMode.BOTH) {
            ConsoleHandler ch = new ConsoleHandler();
            ch.setFormatter(formatter);
            ch.setLevel(Level.ALL);
            logger.addHandler(ch);
        }

        if (mode == LogMode.FILE_ONLY || mode == LogMode.BOTH) {
            try {
                FileHandler fh = new FileHandler(logFilePath, true);
                fh.setFormatter(formatter);
                fh.setLevel(Level.ALL);
                logger.addHandler(fh);
            } catch (IOException e) {
                System.err.println("日志文件创建失败: " + logFilePath);
                e.printStackTrace();
            }
        }

        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        initialized = true;
    }

    // ✅ 获取调用者信息（固定偏移）
    private static String getCallerLocation() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // [0] = getStackTrace
        // [1] = getCallerLocation
        // [2] = logWithLevel
        // [3] = info / infof 等
        // [4] = 用户代码 ✅
        if (stack.length > 4) {
            StackTraceElement frame = stack[4];
            String className = frame.getClassName();
            int dot = className.lastIndexOf('.');
            if (dot != -1) className = className.substring(dot + 1);
            return className + "." + frame.getMethodName() + ":" + frame.getLineNumber();
        }
        return "unknown";
    }

    private static void logWithLevel(Level level, String msg) {
        // 1. 获取 caller
        String caller = getCallerLocation();
        // 2. 存入 ThreadLocal（供 Formatter 使用）
        callerInfo.set(caller);
        try {
            // 3. 记录日志（此时 Formatter 会读取 caller）
            logger.log(level, msg);
        } finally {
            // 4. 清理 ThreadLocal，避免内存泄漏
            callerInfo.remove();
        }
    }

    // ===== 初始化 =====
    public static void init(LogMode mode) {
        init(mode, "app.log");
    }

    public static void init(LogMode mode, String logFilePath) {
        initialize(mode, logFilePath);
    }

    // ===== 日志方法 =====
    public static void info(String msg) { logWithLevel(Level.INFO, msg); }
    public static void warning(String msg) { logWithLevel(Level.WARNING, msg); }
    public static void severe(String msg) { logWithLevel(Level.SEVERE, msg); }
    public static void error(String msg) { logWithLevel(Level.SEVERE, msg); }

    public static void infof(String format, Object... args) {
        logWithLevel(Level.INFO, String.format(format, args));
    }

    public static void warningf(String format, Object... args) {
        logWithLevel(Level.WARNING, String.format(format, args));
    }

    public static void severef(String format, Object... args) {
        logWithLevel(Level.SEVERE, String.format(format, args));
    }

    public static void errorf(String format, Object... args) {
        logWithLevel(Level.SEVERE, String.format(format, args));
    }

    // Fine levels
    public static void fine(String msg) { logWithLevel(Level.FINE, msg); }
    public static void finer(String msg) { logWithLevel(Level.FINER, msg); }
    public static void finest(String msg) { logWithLevel(Level.FINEST, msg); }
    public static void finef(String format, Object... args) { logWithLevel(Level.FINE, String.format(format, args)); }
    public static void fineref(String format, Object... args) { logWithLevel(Level.FINER, String.format(format, args)); }
    public static void finestf(String format, Object... args) { logWithLevel(Level.FINEST, String.format(format, args)); }

    public static void shutdown() {
        for (Handler h : logger.getHandlers()) {
            h.close();
        }
    }
}
