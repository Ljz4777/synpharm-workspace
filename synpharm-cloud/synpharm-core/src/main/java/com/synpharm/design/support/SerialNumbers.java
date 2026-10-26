package com.synpharm.design.support;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 业务流水号生成。
 *
 * <p>格式与设计一致：{@code 前缀 + yyMMddHHmmss + 4 位大写随机十六进制}。
 * 秒级时间 + 随机后缀在单库内足够，且唯一约束（{@code uk_set_no} / {@code uk_job_no}）
 * 会在碰撞时兜住，不会静默产生重复号。
 *
 * @author SynPharm Team
 */
public final class SerialNumbers {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private SerialNumbers() {
    }

    /** 约束集编号，如 {@code CS261014153045A7F3} */
    public static String constraintSetNo() {
        return "CS" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 任务编号，如 {@code J261014153045A7F3} */
    public static String jobNo() {
        return "J" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 项目编号，如 {@code P261014153045A7F3} */
    public static String projectNo() {
        return "P" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 报告编号，如 {@code R261014153045A7F3} */
    public static String reportNo() {
        return "R" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 实验编号，如 {@code W261014153045A7F3} */
    public static String experimentNo() {
        return "W" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 基线快照编号，如 {@code BS261014153045A7F3} */
    public static String baselineSnapshotNo() {
        return "BS" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 生成批次编号，如 {@code GEN261014153045A7F3} */
    public static String generationRunNo() {
        return "GEN" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 生成回放包编号，如 {@code GOS261014153045A7F3} */
    public static String generationSnapshotNo() {
        return "GOS" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 评估冻结编号，如 {@code EV261014153045A7F3} */
    public static String evaluationFreezeNo() {
        return "EV" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 缺口诊断报告编号，如 {@code GAP261014153045A7F3} */
    public static String editGapReportNo() {
        return "GAP" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 编辑批次编号，如 {@code EBT261014153045A7F3} */
    public static String editBatchNo() {
        return "EBT" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 编辑变化报告编号，如 {@code DLT261014153045A7F3} */
    public static String editDeltaReportNo() {
        return "DLT" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 证据包编号，如 {@code EB261025120000A7F3} */
    public static String evidenceBundleNo() {
        return "EB" + LocalDateTime.now().format(TS) + randomHex4();
    }

    /** 决策记录编号，如 {@code DCN261025120000A7F3} */
    public static String decisionNo() {
        return "DCN" + LocalDateTime.now().format(TS) + randomHex4();
    }

    private static String randomHex4() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(HEX[RANDOM.nextInt(HEX.length)]);
        }
        return sb.toString();
    }
}
