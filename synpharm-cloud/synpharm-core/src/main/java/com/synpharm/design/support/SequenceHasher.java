package com.synpharm.design.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 多链序列的去重哈希（06 文档 §4.5）。
 *
 * <p>原来的 {@code sequence_hash} 是「单条序列的 sha256」。这个定义对单链多肽够用，
 * 对抗体与双抗直接失效 —— 抗体的身份是<b>重链 + 轻链的组合</b>：
 * <ul>
 *   <li>只哈希重链：轻链不同的两个分子会被误判为重复，S5 去重会把真候选丢掉；</li>
 *   <li>两条链无关地各存一个哈希：无法表达"这一对才是同一个分子"。</li>
 * </ul>
 *
 * <p>定义：
 * <pre>{@code
 * sequence_hash = sha256( norm(heavy) + "|" + norm(light) + "|" + Σ norm(name:seq) )
 * }</pre>
 * 其中 {@code extra_chains} 按 {@code name} 升序拼接，保证同一条链在不同顺序下得到同一哈希。
 *
 * <p>规范化 = 去全部空白 + 转大写 + 去尾部终止符（{@code *}）。
 * 这三个动作都不是"清洗得更漂亮"，而是去重的必要条件：
 * FASTA 的换行、大小写混写、结尾的 {@code *} 都是同一分子，不规范化就会在 S5 里被当成多个。
 *
 * <p>小分子场景返回 {@code null} —— 它们用 {@code inchikey} 去重，两者不要混用。
 *
 * @author SynPharm Team
 */
public final class SequenceHasher {

    /** 额外链：名字 + 序列 */
    public record Chain(String name, String sequence) {
    }

    private SequenceHasher() {
    }

    /**
     * 计算多链序列哈希。
     *
     * @param heavyChain 重链（可空）
     * @param lightChain 轻链（可空）
     * @param extraChains 额外链（可空）
     * @return 64 位小写十六进制；三条链全空时返回 {@code null}
     */
    public static String hash(String heavyChain, String lightChain, List<Chain> extraChains) {
        String heavy = normalize(heavyChain);
        String light = normalize(lightChain);
        List<Chain> extras = normalizeExtras(extraChains);

        if (heavy.isEmpty() && light.isEmpty() && extras.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append(heavy).append('|').append(light).append('|');
        for (Chain chain : extras) {
            sb.append(chain.name()).append(':').append(chain.sequence()).append('|');
        }
        return sha256(sb.toString());
    }

    /** 单链便捷入口（多肽 / 单域抗体） */
    public static String hashSingle(String sequence) {
        return hash(sequence, null, null);
    }

    /**
     * 规范化一条序列。
     *
     * <p>公开出来是给「显示层」用的：前端要把规范化后的序列展示给用户，
     * 若两端各写一套清洗规则，用户看到的和参与去重计算的就会不一致。
     */
    public static String normalize(String sequence) {
        if (sequence == null || sequence.isBlank()) {
            return "";
        }
        // 去全部空白（FASTA 换行）、转大写、去尾部终止符
        String cleaned = sequence.replaceAll("\\s+", "").toUpperCase();
        int end = cleaned.length();
        while (end > 0 && cleaned.charAt(end - 1) == '*') {
            end--;
        }
        return cleaned.substring(0, end);
    }

    private static List<Chain> normalizeExtras(List<Chain> extraChains) {
        if (extraChains == null || extraChains.isEmpty()) {
            return List.of();
        }
        List<Chain> cleaned = new ArrayList<>(extraChains.size());
        for (Chain chain : extraChains) {
            if (chain == null) {
                continue;
            }
            String seq = normalize(chain.sequence());
            if (seq.isEmpty()) {
                continue;
            }
            String name = chain.name() == null ? "" : chain.name().trim().toUpperCase();
            cleaned.add(new Chain(name, seq));
        }
        // 按名字排序：同一条链在不同输入顺序下必须得到同一哈希，
        // 否则「先给 A 链再给 B 链」与「先 B 后 A」会被当成两个分子
        cleaned.sort(Comparator.comparing(Chain::name));
        return cleaned;
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
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
