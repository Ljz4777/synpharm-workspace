package com.synpharm.design.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容哈希工具。
 *
 * <p>生成与评估两处都要算「内容指纹」，各写一份私有实现的话，某天一边换了
 * 编码或大小写归一化规则，同内容会算出不同哈希，幂等就静默失效了。
 *
 * @author SynPharm Team
 */
public final class Hashes {

    private Hashes() {
    }

    /** SHA-256 十六进制小写；{@code null} 视为空串 */
    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }
}
