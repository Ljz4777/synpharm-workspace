package com.synpharm.design.api.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 基线预检结果（{@code POST /projects/{id}/baseline:preview}）。
 *
 * <p><b>预检不落库</b>：它只回答「现在冻结会缺什么」，让前端在向导里逐项补齐。
 * 与冻结的区别只有一条 —— 冻结会把结果写成不可变的 {@code baseline_snapshot}。
 *
 * @author SynPharm Team
 */
@Data
public class BaselinePreviewResponse {

    /** 八块全部通过（required 的块都 ok） */
    private boolean ready;

    /** 逐块状态，顺序固定为 {@code BaselineBlock} 的声明顺序 */
    private List<BlockStatus> blocks = new ArrayList<>();

    /** 缺项清单；{@link #ready} 为 true 时为空 */
    private List<Gap> gaps = new ArrayList<>();

    /**
     * 当前内容的 sha256。
     *
     * <p>前端可据此判断「预检之后有没有人改过项目」—— 与冻结时的 hash 不一致说明有并发修改。
     */
    private String contentHash;

    /** 单个块的状态 */
    @Data
    public static class BlockStatus {

        /** BaselineBlock.code */
        private String code;

        /** BaselineBlock.label */
        private String label;

        /** 是否必需 */
        private boolean required;

        /** OK / MISSING / WARN */
        private String state;

        /** 一句话说明（OK 时是「2 个靶点，主靶点 EGFR」，WARN 时是原因） */
        private String message;
    }

    /**
     * 一个缺项（或警告）。
     *
     * <p>{@code block} + {@code code} 让前端能定位到向导的哪一步。
     */
    @Data
    public static class Gap {

        /** 所属块 */
        private String block;

        /** 机器可读码：NO_TARGET / NO_PRIMARY_TARGET / NO_STRUCTURE / ... */
        private String code;

        /** 直接展示给用户的中文说明（红线 R2：不做隐藏规则） */
        private String message;

        /** ERROR=阻断冻结 / WARN=可冻结但有风险 */
        private String severity;

        public static Gap error(String block, String code, String message) {
            Gap g = new Gap();
            g.setBlock(block);
            g.setCode(code);
            g.setMessage(message);
            g.setSeverity("ERROR");
            return g;
        }

        public static Gap warn(String block, String code, String message) {
            Gap g = new Gap();
            g.setBlock(block);
            g.setCode(code);
            g.setMessage(message);
            g.setSeverity("WARN");
            return g;
        }
    }
}
