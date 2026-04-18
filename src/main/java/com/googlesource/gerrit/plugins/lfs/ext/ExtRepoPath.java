package com.googlesource.gerrit.plugins.lfs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.googlesource.gerrit.plugins.lfs.ExtLogger;

public class ExtRepoPath {

    private final String repoName;      // 仅 raw 模式有效
    private final String shortHash;     // 仅 hashed 模式有效
    private final String id;            // always starts with '/'
    private final boolean isControlPath;
    private final String repoHash;      // 仅 raw 模式有效
    private final boolean isHashedMode;

    public static final int SHORT_HASH_LENGTH = 16;

    // ===== 构造函数1：默认 raw 模式 (isHashedMode = false) =====
    public ExtRepoPath(String path) {
        this(false, path);
    }

    // ===== 构造函数2：显式指定模式 =====
    public ExtRepoPath(boolean isHashedMode, String path) {
        this.isHashedMode = isHashedMode;

        if (path == null) {
            ExtLogger.severe("constructor error: path is null");
            throw new IllegalArgumentException("Path cannot be null");
        }
        if (path.isEmpty()) {
            ExtLogger.severe("constructor error: path is empty");
            throw new IllegalArgumentException("Path cannot be empty");
        }

        if (isHashedMode) {
            // === 哈希模式：清理开头的 '/'（如果存在）===
            String cleanPath = path.charAt(0) == '/' ? path.substring(1) : path;
            if (cleanPath.isEmpty()) {
                ExtLogger.severe("invalid hashed path: '" + path + "' -> empty after trimming '/'");
                throw new IllegalArgumentException("Hashed path cannot be just '/'");
            }

            int slashIndex = cleanPath.indexOf('/');
            if (slashIndex <= 0 || slashIndex == cleanPath.length() - 1) {
                ExtLogger.severe("invalid hashed path: '" + path + "' (must be 'shortHash/idPart')");
                throw new IllegalArgumentException("Hashed path must be 'shortHash/idPart', e.g., 'a1b2c3/file'");
            }

            this.shortHash = cleanPath.substring(0, slashIndex);
            this.id = "/" + cleanPath.substring(slashIndex + 1);
            this.repoName = null;
            this.repoHash = null;
            this.isControlPath = false;

            ExtLogger.info("(hashed mode): shortHash='" + shortHash + "', id='" + this.id + "'");

        } else {
            // === 原始路径模式：清理开头的 '/' ===
            String cleanPath = path.charAt(0) == '/' ? path.substring(1) : path;
            if (cleanPath.isEmpty()) {
                ExtLogger.severe("invalid raw path: '" + path + "' -> empty after trimming '/'");
                throw new IllegalArgumentException("Path cannot be just '/'");
            }

            boolean isLfs = cleanPath.contains("info/lfs/");
            String rawRepo;

            if (isLfs) {
                int idx = cleanPath.indexOf("info/lfs/");
                rawRepo = cleanPath.substring(0, idx);
                this.id = null;
            } else {
                int lastSlash = cleanPath.lastIndexOf('/');
                if (lastSlash == -1) {
                    ExtLogger.severe("invalid raw path: '" + path + "' (must contain at least one '/')");
                    throw new IllegalArgumentException("Object path must contain at least one '/'");
                }
                rawRepo = cleanPath.substring(0, lastSlash);
                String lastPart = cleanPath.substring(lastSlash + 1);
                this.id = "/" + lastPart;
            }

            // 去掉 repoName 结尾的 '/'
            if (rawRepo.endsWith("/")) {
                rawRepo = rawRepo.substring(0, rawRepo.length() - 1);
            }
            if (rawRepo.isEmpty()) {
                ExtLogger.severe("invalid raw path: repoName is empty after trimming");
                throw new IllegalArgumentException("Repo name cannot be empty");
            }

            // 去掉 repoName 结尾的 '.git' 如果存在
            // 不同版本的git，可能会携带或不携带 '.git' 后缀，因此需要兼容两者
            if (rawRepo.length() > 4 && rawRepo.endsWith(".git")) {
                this.repoName = rawRepo.substring(0, rawRepo.length() - 4);
            } else {
                this.repoName = rawRepo;
            }

            this.repoHash = sha256(this.repoName);
            this.isControlPath = isLfs;
            this.shortHash = null;

            if (isControlPath) {
                ExtLogger.info("(raw mode): repoName='" + repoName + "(" + rawRepo + ")', type=CONTROL");
            } else {
                ExtLogger.info("(raw mode): repoName='" + repoName + "(" + rawRepo + ")', id='" + id + "', shortHash='" + getShortRepoHash() + "'");
            }
        }
    }

    // ===== Getters =====
    public String getRepoName() {
        return repoName;
    }

    public String getId() {
        return id;
    }

    public boolean isControlPath() {
        return isControlPath;
    }

    public String getRepoHash() {
        return repoHash;
    }

    public String getShortRepoHash() {
        if (isHashedMode) {
            return shortHash;
        } else {
            return repoHash != null ? repoHash.substring(0, Math.min(SHORT_HASH_LENGTH, repoHash.length())) : null;
        }
    }

    public boolean isHashedMode() {
        return isHashedMode;
    }

    public String toHashedPath() {
        if (isControlPath) {
            ExtLogger.severe("Control path has no ID");
            throw new IllegalStateException("Control path has no ID");
        }
        return getShortRepoHash() + id; // id already starts with '/', e.g., "a1b2.../file"
    }

    // ===== Helper =====
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hashBytes) {
                String hexStr = Integer.toHexString(0xff & b);
                if (hexStr.length() == 1) hex.append('0');
                hex.append(hexStr);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 computation failed", e);
        }
    }

    @Override
    public String toString() {
        if (isHashedMode) {
            return String.format("ExtRepoPath{[hashed] shortHash='%s', id='%s'}", shortHash, id);
        } else if (isControlPath) {
            return String.format("ExtRepoPath{repoName='%s', [CONTROL]}", repoName);
        } else {
            return String.format("ExtRepoPath{repoName='%s', id='%s', shortHash='%s'}",
                    repoName, id, getShortRepoHash());
        }
    }
}