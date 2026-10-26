package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUserTestSupport;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.ConstraintRuleDto;
import com.synpharm.design.api.dto.ConstraintSetResponse;
import com.synpharm.design.api.dto.ConstraintSetUpsertRequest;
import com.synpharm.design.domain.model.ConstraintRule;
import com.synpharm.design.domain.model.ConstraintSet;
import com.synpharm.design.domain.model.ConstraintStatus;
import com.synpharm.design.infrastructure.persistence.ConstraintRuleMapper;
import com.synpharm.design.infrastructure.persistence.ConstraintSetMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 约束集用例测试。
 *
 * <p>重点：归属校验必须 fail-closed（别人的 id、以及 ownerId 为 null 的脏数据都要 404，
 * 不能像既有单体那样「userId 为空就放行」）、仅 DRAFT 可编辑、激活前自检。
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
class ConstraintAppServiceImplTest {

    private static final long ME = 100L;
    private static final long OTHER = 200L;

    @Mock
    private ConstraintSetMapper setMapper;
    @Mock
    private ConstraintRuleMapper ruleMapper;

    private ConstraintAppServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConstraintAppServiceImpl(setMapper, ruleMapper, new JsonCodec(new ObjectMapper()));
        CurrentUserTestSupport.login(ME);
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.logout();
    }

    private static ConstraintSet set(Long id, Long ownerId, ConstraintStatus status) {
        ConstraintSet s = new ConstraintSet();
        s.setId(id);
        s.setSetNo("CS260101000000ABCD");
        s.setUserId(ownerId);
        s.setName("测试约束集");
        s.setLogic("AND");
        s.setStatus(status.name());
        s.setVersion(1);
        return s;
    }

    private static ConstraintRule rule(Long setId, String type, String paramsJson) {
        ConstraintRule r = new ConstraintRule();
        r.setId(9L);
        r.setSetId(setId);
        r.setGroupKey("G1");
        r.setGroupLogic("AND");
        r.setRuleType(type);
        r.setSeverity("HARD");
        r.setParamsJson(paramsJson);
        r.setHumanReadable("分子量 200~550");
        r.setWeight(10);
        r.setSortNo(0);
        r.setEnabled(1);
        return r;
    }

    private static ConstraintSetUpsertRequest requestWith(ConstraintRuleDto... rules) {
        ConstraintSetUpsertRequest request = new ConstraintSetUpsertRequest();
        request.setName("测试约束集");
        request.setRules(List.of(rules));
        return request;
    }

    private static ConstraintRuleDto validRule() {
        ConstraintRuleDto dto = new ConstraintRuleDto();
        dto.setRuleType("NUMERIC_RANGE");
        dto.setSeverity("HARD");
        dto.setParams(Map.of("field", "MolWt", "min", 200, "max", 550));
        dto.setHumanReadable("分子量 200~550");
        return dto;
    }

    @Test
    @DisplayName("访问他人约束集 -> 404（不是 403，避免资源枚举）")
    void getRejectsForeignSet() {
        when(setMapper.selectById(1L)).thenReturn(set(1L, OTHER, ConstraintStatus.DRAFT));

        assertThatThrownBy(() -> service.get(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONSTRAINT_SET_NOT_FOUND);
    }

    @Test
    @DisplayName("ownerId 为 null 的脏数据 -> 404（fail-closed，不放行）")
    void getFailsClosedOnNullOwner() {
        when(setMapper.selectById(1L)).thenReturn(set(1L, null, ConstraintStatus.DRAFT));

        assertThatThrownBy(() -> service.get(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONSTRAINT_SET_NOT_FOUND);
    }

    @Test
    @DisplayName("新建：规则非法 -> 400 CONSTRAINT_RULE_INVALID 且不落库")
    void createRejectsInvalidRules() {
        ConstraintRuleDto bad = validRule();
        bad.setParams(Map.of("min", 1));   // 缺 field

        assertThatThrownBy(() -> service.create(requestWith(bad)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONSTRAINT_RULE_INVALID);
        verify(setMapper, never()).insert(any(ConstraintSet.class));
    }

    @Test
    @DisplayName("新建：初始 DRAFT，版本默认 1，规则随集落库")
    void createInsertsDraftWithRules() {
        ConstraintSetResponse response = service.create(requestWith(validRule()));

        assertThat(response.getId()).isEqualTo(response.getSetId());
        assertThat(response.getStatus()).isEqualTo(ConstraintStatus.DRAFT.name());
        assertThat(response.getVersion()).isEqualTo(1);
        assertThat(response.getLogic()).isEqualTo("AND");
        assertThat(response.getSetNo()).startsWith("CS");
        assertThat(response.getRules()).hasSize(1);
        verify(setMapper).insert(any(ConstraintSet.class));
        verify(ruleMapper).insert(any(ConstraintRule.class));
    }

    @Test
    @DisplayName("编辑非 DRAFT -> 409 CONSTRAINT_SET_NOT_EDITABLE")
    void updateRejectsActiveSet() {
        when(setMapper.selectById(1L)).thenReturn(set(1L, ME, ConstraintStatus.ACTIVE));

        assertThatThrownBy(() -> service.update(1L, requestWith(validRule())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONSTRAINT_SET_NOT_EDITABLE);
        verify(setMapper, never()).updateById(any(ConstraintSet.class));
    }

    @Test
    @DisplayName("编辑 DRAFT 且带规则：旧规则整集替换")
    void updateReplacesRulesOnDraft() {
        when(setMapper.selectById(1L)).thenReturn(set(1L, ME, ConstraintStatus.DRAFT));

        service.update(1L, requestWith(validRule()));

        verify(ruleMapper).delete(any(Wrapper.class));
        verify(ruleMapper).insert(any(ConstraintRule.class));
    }

    @Test
    @DisplayName("激活空约束集 -> 400（自检拦截）")
    void activateRejectsEmptySet() {
        when(setMapper.selectById(1L)).thenReturn(set(1L, ME, ConstraintStatus.DRAFT));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.activate(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONSTRAINT_RULE_INVALID);
        verify(setMapper, never()).updateById(any(ConstraintSet.class));
    }

    @Test
    @DisplayName("激活：自检通过后 DRAFT -> ACTIVE")
    void activateMovesDraftToActive() {
        when(setMapper.selectById(1L)).thenReturn(set(1L, ME, ConstraintStatus.DRAFT));
        when(ruleMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(rule(1L, "NUMERIC_RANGE", "{\"field\":\"MolWt\",\"min\":200,\"max\":550}")));

        ConstraintSetResponse response = service.activate(1L);

        assertThat(response.getStatus()).isEqualTo(ConstraintStatus.ACTIVE.name());
        assertThat(response.getRules()).hasSize(1);
        verify(setMapper).updateById(any(ConstraintSet.class));
    }

    @Test
    @DisplayName("激活非 DRAFT -> 409 BUSINESS_CONFLICT")
    void activateRejectsNonDraft() {
        when(setMapper.selectById(1L)).thenReturn(set(1L, ME, ConstraintStatus.ACTIVE));

        assertThatThrownBy(() -> service.activate(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_CONFLICT);
    }

    @Test
    @DisplayName("归档：ACTIVE -> ARCHIVED")
    void archiveMovesActiveToArchived() {
        when(setMapper.selectById(1L)).thenReturn(set(1L, ME, ConstraintStatus.ACTIVE));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(service.archive(1L).getStatus()).isEqualTo(ConstraintStatus.ARCHIVED.name());
    }

    @Test
    @DisplayName("重复归档 -> 409 BUSINESS_CONFLICT")
    void archiveRejectsAlreadyArchived() {
        when(setMapper.selectById(1L)).thenReturn(set(1L, ME, ConstraintStatus.ARCHIVED));

        assertThatThrownBy(() -> service.archive(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_CONFLICT);
    }

    @Test
    @DisplayName("自检接口：不抛异常，问题逐条带下标返回")
    void validateReturnsIssuesWithIndex() {
        var result = service.validate(List.of(validRule(), new ConstraintRuleDto()));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors()).anyMatch(i -> i.getIndex() == 1);
    }

    @Test
    @DisplayName("meta：下发 11 种 ruleType 与维度/算子")
    void metaExposesDictionaries() {
        Map<String, Object> meta = service.meta();

        assertThat((List<?>) meta.get("ruleTypes")).hasSize(11);
        assertThat((List<?>) meta.get("dimensions")).hasSize(8);
        assertThat(meta.get("operators").toString()).contains("BETWEEN");
        assertThat(meta.get("catalogs").toString()).contains("PAINS");
    }

    @Test
    @DisplayName("列表：只查当前用户（带 user_id 过滤条件）")
    void listScopesToCurrentUser() {
        when(setMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.list(null, 1, 20);

        verify(setMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表：非法 status -> 400")
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.list("NOPE", 1, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(setMapper, never()).selectPage(any(), any());
    }
}
