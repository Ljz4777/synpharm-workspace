package com.synpharm.design.domain.model;

import java.util.Arrays;

/**
 * 任务类型（契约 {@code JobType}）。
 *
 * <p>类型到队列的映射来自《03-实现级详细设计》§7 的队列表；该表没有为
 * {@code STRUCTURE_FETCH} 与 {@code EXPORT} 定义队列，因此这两类目前<strong>不能</strong>投递
 * （{@link #routingKey()} 会直接报错而不是悄悄不投）。
 *
 * @author SynPharm Team
 */
public enum JobType {

    GENERATION,
    EVALUATION,
    POCKET_DETECT,
    STRUCTURE_FETCH,
    REPORT,
    FEEDBACK_CALIBRATE,
    EXPORT;

    /** 该类型对应的 MQ 路由键（direct 交换机下与队列名一致） */
    public String routingKey() {
        return switch (this) {
            case GENERATION -> "design.generate";
            case EVALUATION -> "design.evaluate";
            case POCKET_DETECT -> "design.pocket";
            case REPORT -> "design.report";
            case FEEDBACK_CALIBRATE -> "design.feedback";
            case STRUCTURE_FETCH, EXPORT -> throw new UnsupportedOperationException(
                    "任务类型 " + name() + " 未在《03-实现级详细设计》§7 定义队列，不能投递 MQ");
        };
    }

    /** 是否为「进 MQ 由计算层消费」的异步任务 */
    public boolean isAsync() {
        return this != STRUCTURE_FETCH && this != EXPORT;
    }

    public static boolean isValid(String raw) {
        return raw != null && Arrays.stream(values()).anyMatch(v -> v.name().equals(raw));
    }

    public static String names() {
        return Arrays.toString(values());
    }
}
