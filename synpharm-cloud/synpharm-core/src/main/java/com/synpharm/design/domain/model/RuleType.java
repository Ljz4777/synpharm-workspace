package com.synpharm.design.domain.model;

/**
 * 约束规则类型（11 种）。每种对应的 {@code params_json} 结构见
 * {@code synpharm-deploy/sql/18_constraint_rule.sql} 尾注。
 *
 * @author SynPharm Team
 */
public enum RuleType {

    /** 数值区间：{@code {"field":"MolWt","min":200,"max":550}} */
    NUMERIC_RANGE,
    /** 必须含某 SMARTS：{@code {"smarts":"S(=O)(=O)N","name":"磺酰胺"}} */
    SMARTS_REQUIRED,
    /** 禁止含某 SMARTS */
    SMARTS_FORBIDDEN,
    /** 必须含某骨架：{@code {"scaffoldSmiles":"...","allowSubstituent":true}} */
    SCAFFOLD_REQUIRED,
    /** 禁止含某骨架 */
    SCAFFOLD_FORBIDDEN,
    /** 走知识库过滤器：{@code {"catalog":"PAINS","mode":"must_pass"}} */
    FILTER_CATALOG,
    /** 与参考分子相似度区间：{@code {"refSmiles":"...","metric":"TANIMOTO","min":0.4,"max":0.8}} */
    SIMILARITY_RANGE,
    /** 与某个分子集合的相似度上限：{@code {"setIds":[1,2],"metric":"TANIMOTO","max":0.6}} */
    SIMILARITY_TO_SET,
    /** 环数：{@code {"min":1,"max":5,"ringType":"AROMATIC"}} */
    RING_COUNT,
    /** 取代基白名单：{@code {"allowedSmarts":["[F,Cl,Br,I]","[OH]"]}} */
    SUBSTITUENT_ALLOWED,
    /** 复用规则集：{@code {"ruleSetCode":"LIPINSKI","mode":"must_pass"}} */
    RULE_SET;

    public static RuleType parse(String raw) {
        for (RuleType t : values()) {
            if (t.name().equalsIgnoreCase(raw == null ? null : raw.trim())) {
                return t;
            }
        }
        return null;
    }
}
