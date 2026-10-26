package com.synpharm.design.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则自检结果（{@code POST /constraints/validate} 的 data）。
 *
 * <p>失败也返回 HTTP 200 + {@code code=0}：这是「编辑器实时反馈」接口，
 * 用户边填边看提示，属于正常交互而不是请求错误。真正的错误码留给「提交时校验不通过」。
 *
 * @author SynPharm Team
 */
@Data
public class RuleValidationResponse {

    /** 全部通过 */
    private boolean valid;

    /** 逐条问题，{@code index} 对应请求数组下标 */
    private List<RuleIssue> errors = new ArrayList<>();

    @Data
    public static class RuleIssue {

        private int index;

        private String ruleType;

        private String message;

        public RuleIssue() {
        }

        public RuleIssue(int index, String ruleType, String message) {
            this.index = index;
            this.ruleType = ruleType;
            this.message = message;
        }
    }

    public static RuleValidationResponse of(List<List<String>> allProblems, List<String> ruleTypes) {
        RuleValidationResponse response = new RuleValidationResponse();
        for (int i = 0; i < allProblems.size(); i++) {
            String type = i < ruleTypes.size() ? ruleTypes.get(i) : null;
            for (String message : allProblems.get(i)) {
                response.getErrors().add(new RuleIssue(i, type, message));
            }
        }
        response.setValid(response.getErrors().isEmpty());
        return response;
    }
}
