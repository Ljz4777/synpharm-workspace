package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUserTestSupport;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.BindingPocketResponse;
import com.synpharm.design.api.dto.TargetResponse;
import com.synpharm.design.api.dto.TargetStructureResponse;
import com.synpharm.design.domain.model.BindingPocket;
import com.synpharm.design.domain.model.Target;
import com.synpharm.design.domain.model.TargetStructure;
import com.synpharm.design.infrastructure.persistence.BindingPocketMapper;
import com.synpharm.design.infrastructure.persistence.TargetMapper;
import com.synpharm.design.infrastructure.persistence.TargetStructureMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 靶点库读路径测试。
 *
 * <p>三条要点：
 * <ol>
 *   <li><b>子项批量加载</b>：无论一页有多少靶点、每个靶点有多少结构，结构查询与口袋查询
 *       各只能有 **1 次**。逐行查会退化成 1+N+N*M 次往返，而且这种退化在功能测试里
 *       「看起来完全正常」—— 只能靠断言调用次数拦。</li>
 *   <li><b>非法 status 必须 400 且不查库</b>：status 是封闭枚举，静默忽略会让用户拿到
 *       **没筛过的全量列表**却以为筛过了。</li>
 *   <li><b>「结构不存在」与「结构没有口袋」必须可区分</b>：前者 404，后者 200 + 空数组。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TargetAppServiceImplTest {

    private static final long TARGET_A = 1L;
    private static final long ME = 100L;
    private static final long TARGET_B = 2L;
    private static final long STRUCT_A1 = 11L;
    private static final long STRUCT_A2 = 12L;
    private static final long STRUCT_B1 = 21L;

    @Mock private TargetMapper targetMapper;
    @Mock private TargetStructureMapper targetStructureMapper;
    @Mock private BindingPocketMapper bindingPocketMapper;
    @Spy private JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());

    @InjectMocks private TargetAppServiceImpl service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 靠 TableInfo 把方法引用翻成列名；单测里没有 Spring/MyBatis 上下文，
        // 手工注册后才能真正断言下发的过滤条件，而不是只断言「调过一次」
        registerTable(Target.class);
        registerTable(TargetStructure.class);
        registerTable(BindingPocket.class);
        CurrentUserTestSupport.login(ME);
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.logout();
    }

    private static void registerTable(Class<?> entity) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
    }

    // ------------------------------------------------------------------ 登录守卫

    @Test
    @DisplayName("无身份：三个端点全部 401，且不查库（不靠网关兜底，服务自己 fail-closed）")
    void rejectsAnonymousCallers() {
        CurrentUserTestSupport.logout();

        assertThatThrownBy(() -> service.list(null, null, null, null, 1, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> service.get(TARGET_A))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> service.listPockets(STRUCT_A1))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(targetMapper, never()).selectPage(any(), any());
        verify(targetStructureMapper, never()).selectById(any());
    }

    // ------------------------------------------------------------------ 列表过滤

    @Test
    @DisplayName("列表：过滤条件与排序按下发到 SQL（status 转小写以匹配库中取值）")
    void listAppliesFilters() {
        stubPage(List.of(), 0);

        service.list("EGFR", "受体酪氨酸激酶", "肿瘤", "supported", 1, 20);

        String sql = capturedSql();
        assertThat(sql).contains("status").contains("target_type").contains("disease_area");
        AbstractWrapper<Target, ?, ?> query = capturedPageWrapper();
        assertThat(query.getParamNameValuePairs().values())
                .contains("supported", "受体酪氨酸激酶", "肿瘤");
    }

    @Test
    @DisplayName("列表：非法 status -> 400，且一次库都不查")
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.list(null, null, null, "NOPE", 1, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(targetMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表：status 大小写不敏感（SUPPORTED 也认），但下发值仍是库中的小写")
    void listStatusIsCaseInsensitive() {
        stubPage(List.of(), 0);

        service.list(null, null, null, "SUPPORTED", 1, 20);

        assertThat(capturedPageWrapper().getParamNameValuePairs().values()).contains("supported");
    }

    @Test
    @DisplayName("列表：关键词 <=3 字符走包含匹配，>3 字符走前缀匹配（规范 §4.4 禁止全表扫）")
    void listKeywordSwitchesToPrefixWhenLong() {
        stubPage(List.of(), 0);
        service.list("EGF", null, null, null, 1, 20);
        // MyBatis-Plus 的 like 系列都是 `col LIKE ?`，区别只在参数值怎么包：
        // like -> %kw%，likeRight -> kw%
        assertThat(paramValues()).contains("%EGF%");

        stubPage(List.of(), 0);
        service.list("EGFR", null, null, null, 1, 20);
        assertThat(paramValues()).contains("EGFR%").doesNotContain("%EGFR%");
    }

    @Test
    @DisplayName("列表：空白关键词与空白分类不参与过滤（不下发恒真条件）")
    void listIgnoresBlankFilters() {
        stubPage(List.of(), 0);

        service.list("   ", "  ", null, null, 1, 20);

        String sql = capturedSql();
        assertThat(sql).doesNotContain("uniprot_id").doesNotContain("target_type");
    }

    // ------------------------------------------------------------------ 子项批量加载

    @Test
    @DisplayName("列表：结构与口袋各只查 1 次，且不随页大小增长（3 条 SQL 上限）")
    void listBatchesChildren() {
        stubPage(List.of(target(TARGET_A), target(TARGET_B)), 2);
        when(targetStructureMapper.selectList(any())).thenReturn(List.of(
                structure(STRUCT_A1, TARGET_A), structure(STRUCT_A2, TARGET_A), structure(STRUCT_B1, TARGET_B)));
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of(
                pocket(101L, STRUCT_A1, 1), pocket(102L, STRUCT_A1, 2), pocket(201L, STRUCT_B1, 1)));

        PageResult<TargetResponse> result = service.list(null, null, null, null, 1, 20);

        verify(targetStructureMapper, times(1)).selectList(any());
        verify(bindingPocketMapper, times(1)).selectList(any());
        assertThat(result.list()).hasSize(2);
        assertThat(result.list().get(0).getStructures()).hasSize(2);
        assertThat(result.list().get(0).getStructures().get(0).getPockets()).hasSize(2);
        assertThat(result.list().get(1).getStructures()).hasSize(1);
        assertThat(result.list().get(1).getStructures().get(0).getPockets()).hasSize(1);
    }

    @Test
    @DisplayName("列表：子项按属主分组，不会串到别的靶点/结构下")
    void listGroupsChildrenByOwner() {
        stubPage(List.of(target(TARGET_A), target(TARGET_B)), 2);
        when(targetStructureMapper.selectList(any())).thenReturn(List.of(
                structure(STRUCT_A1, TARGET_A), structure(STRUCT_B1, TARGET_B)));
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of(
                pocket(101L, STRUCT_A1, 1), pocket(201L, STRUCT_B1, 1)));

        PageResult<TargetResponse> result = service.list(null, null, null, null, 1, 20);

        assertThat(result.list().get(0).getStructures()).extracting("id").containsExactly(STRUCT_A1);
        assertThat(result.list().get(1).getStructures()).extracting("id").containsExactly(STRUCT_B1);
        assertThat(result.list().get(0).getStructures().get(0).getPockets()).extracting("id").containsExactly(101L);
        assertThat(result.list().get(1).getStructures().get(0).getPockets()).extracting("id").containsExactly(201L);
    }

    @Test
    @DisplayName("列表：空页 -> 连子项查询都不发（不发恒假的 IN (NULL)）")
    void listSkipsChildQueriesWhenPageEmpty() {
        stubPage(List.of(), 0);

        PageResult<TargetResponse> result = service.list(null, null, null, null, 1, 20);

        assertThat(result.list()).isEmpty();
        verify(targetStructureMapper, never()).selectList(any());
        verify(bindingPocketMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("列表：结构存在但无口袋 -> 空数组（不是 null，前端可直接遍历）")
    void listYieldsEmptyPocketsWhenNoneExist() {
        stubPage(List.of(target(TARGET_A)), 1);
        when(targetStructureMapper.selectList(any())).thenReturn(List.of(structure(STRUCT_A1, TARGET_A)));
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of());

        PageResult<TargetResponse> result = service.list(null, null, null, null, 1, 20);

        assertThat(result.list().get(0).getStructures().get(0).getPockets()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("列表：靶点一个结构都没有 -> 连口袋查询都不发")
    void listSkipsPocketQueryWhenNoStructures() {
        stubPage(List.of(target(TARGET_A)), 1);
        when(targetStructureMapper.selectList(any())).thenReturn(List.of());

        service.list(null, null, null, null, 1, 20);

        verify(bindingPocketMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("列表：口袋查询的 IN 里只带本页结构 id（不扩大扫描范围）")
    void listScopesPocketQueryToPageStructures() {
        stubPage(List.of(target(TARGET_A), target(TARGET_B)), 2);
        when(targetStructureMapper.selectList(any())).thenReturn(List.of(
                structure(STRUCT_A1, TARGET_A), structure(STRUCT_B1, TARGET_B)));
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of());

        service.list(null, null, null, null, 1, 20);

        assertThat(pocketQueryParamValues()).containsExactlyInAnyOrder(STRUCT_A1, STRUCT_B1);
    }

    // -------------------------------------------------------------------- 详情

    @Test
    @DisplayName("详情：不存在 -> 404 TARGET_NOT_FOUND，且不继续查结构与口袋")
    void getRejectsMissingTarget() {
        when(targetMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(999L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TARGET_NOT_FOUND);

        verify(targetStructureMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("详情：含结构与口袋；契约外的 4 个描述性字段也要带出来")
    void getReturnsNestedTree() {
        Target entity = target(TARGET_A);
        entity.setDescription("表皮生长因子受体");
        entity.setPathway("EGFR 信号通路");
        entity.setRelatedDiseases("非小细胞肺癌");
        entity.setKnownDrugs("吉非替尼");
        when(targetMapper.selectById(TARGET_A)).thenReturn(entity);
        when(targetStructureMapper.selectList(any())).thenReturn(List.of(structure(STRUCT_A1, TARGET_A)));
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of(pocket(101L, STRUCT_A1, 1)));

        TargetResponse response = service.get(TARGET_A);

        assertThat(response.getDescription()).isEqualTo("表皮生长因子受体");
        assertThat(response.getPathway()).isEqualTo("EGFR 信号通路");
        assertThat(response.getRelatedDiseases()).isEqualTo("非小细胞肺癌");
        assertThat(response.getKnownDrugs()).isEqualTo("吉非替尼");
        assertThat(response.getSequence()).isNotBlank();
        assertThat(response.getStructures()).hasSize(1);
        assertThat(response.getStructures().get(0).getPockets()).hasSize(1);
    }

    @Test
    @DisplayName("对外 DTO 不得包含 filePath（容器内落盘路径，泄漏目录结构且前端拿不到文件）")
    void structureDtoDoesNotLeakLocalPath() {
        assertThat(TargetStructureResponse.class.getDeclaredFields())
                .extracting("name")
                .doesNotContain("filePath");

        TargetStructure entity = structure(STRUCT_A1, TARGET_A);
        entity.setFilePath("/data/structures/1m17.cif");
        stubPage(List.of(target(TARGET_A)), 1);
        when(targetStructureMapper.selectList(any())).thenReturn(List.of(entity));
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of());

        TargetStructureResponse response = service.list(null, null, null, null, 1, 20)
                .list().get(0).getStructures().get(0);

        assertThat(response.getSourceKey()).isEqualTo("1M17:A");
        assertThat(response.getFileAssetId()).isNull();
    }

    // -------------------------------------------------------------- 口袋列表

    @Test
    @DisplayName("口袋列表：结构不存在 -> 404 STRUCTURE_NOT_FOUND，不返回 200+空数组")
    void pocketsRejectsMissingStructure() {
        when(targetStructureMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.listPockets(999L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STRUCTURE_NOT_FOUND);

        verify(bindingPocketMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("口袋列表：结构存在但无口袋 -> 200 + 空列表（与 404 语义分开）")
    void pocketsYieldsEmptyListWhenNoneExist() {
        when(targetStructureMapper.selectById(STRUCT_A1)).thenReturn(structure(STRUCT_A1, TARGET_A));
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listPockets(STRUCT_A1)).isEmpty();
    }

    // -------------------------------------------------------------------- 映射

    @Test
    @DisplayName("映射：residues_json 解成对象数组，0/1 解成布尔，DECIMAL 保留精度")
    void mapsPocketFields() {
        when(targetStructureMapper.selectById(STRUCT_A1)).thenReturn(structure(STRUCT_A1, TARGET_A));
        BindingPocket pocket = pocket(101L, STRUCT_A1, 1);
        pocket.setDruggability(new BigDecimal("0.8123"));
        pocket.setVolume(new BigDecimal("1234.56"));
        pocket.setCenterX(new BigDecimal("12.345"));
        pocket.setResiduesJson("[{\"chain\":\"A\",\"resno\":745,\"resname\":\"LYS\"},{\"chain\":\"A\",\"resno\":790,\"resname\":\"THR\"}]");
        pocket.setIsSelected(1);
        pocket.setNote("已知 ATP 位点");
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of(pocket));

        BindingPocketResponse response = service.listPockets(STRUCT_A1).get(0);

        assertThat(response.getResidues()).hasSize(2);
        assertThat(response.getResidues().get(0)).containsEntry("chain", "A").containsEntry("resno", 745);
        assertThat(response.getIsSelected()).isTrue();
        assertThat(response.getDruggability()).isEqualByComparingTo("0.8123");
        assertThat(response.getVolume()).isEqualByComparingTo("1234.56");
        assertThat(response.getCenterX()).isEqualByComparingTo("12.345");
        assertThat(response.getNote()).isEqualTo("已知 ATP 位点");
    }

    @Test
    @DisplayName("映射：residues_json 为 null -> 空数组；is_selected=0 -> false")
    void mapsNumericFlagsFalseWhenZero() {
        when(targetStructureMapper.selectById(STRUCT_A1)).thenReturn(structure(STRUCT_A1, TARGET_A));
        BindingPocket pocket = pocket(101L, STRUCT_A1, 1);
        pocket.setResiduesJson(null);
        pocket.setIsSelected(0);
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of(pocket));

        BindingPocketResponse response = service.listPockets(STRUCT_A1).get(0);

        assertThat(response.getResidues()).isEmpty();
        assertThat(response.getIsSelected()).isFalse();
    }

    @Test
    @DisplayName("映射：residues_json 写坏了必须抛错，不能静默当成空数组")
    void rejectsMalformedResiduesJson() {
        when(targetStructureMapper.selectById(STRUCT_A1)).thenReturn(structure(STRUCT_A1, TARGET_A));
        BindingPocket pocket = pocket(101L, STRUCT_A1, 1);
        pocket.setResiduesJson("{不是数组");
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of(pocket));

        assertThatThrownBy(() -> service.listPockets(STRUCT_A1))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("映射：is_preferred 0/1 与 null 都正确落到布尔")
    void mapsStructureFlags() {
        stubPage(List.of(target(TARGET_A)), 1);
        TargetStructure preferred = structure(STRUCT_A1, TARGET_A);
        preferred.setIsPreferred(1);
        TargetStructure unknown = structure(STRUCT_A2, TARGET_A);
        unknown.setIsPreferred(null);
        when(targetStructureMapper.selectList(any())).thenReturn(List.of(preferred, unknown));
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of());

        List<TargetStructureResponse> structures = service.list(null, null, null, null, 1, 20)
                .list().get(0).getStructures();

        assertThat(structures).hasSize(2);
        Map<Long, Boolean> preferredById = structures.stream()
                .collect(Collectors.toMap(TargetStructureResponse::getId, TargetStructureResponse::getIsPreferred));
        assertThat(preferredById).containsEntry(STRUCT_A1, true).containsEntry(STRUCT_A2, false);
        // fileAssetId 为 null 时保持 null（尚未落资产库），不要编成 0
        assertThat(structures).extracting("fileAssetId").containsOnlyNulls();
    }

    // ------------------------------------------------------------------ helpers

    private void stubPage(List<Target> records, long total) {
        // 用 doAnswer(...).when(...) 而不是 when(...).thenAnswer(...)：后者在「同一个测试里
        // 二次打桩」时会先执行上一个 answer（参数是 any() 的 null），直接 NPE
        doAnswer(invocation -> {
            Page<Target> page = invocation.getArgument(0);
            page.setRecords(records);
            page.setTotal(total);
            return page;
        }).when(targetMapper).selectPage(any(), any());
    }

    private String capturedSql() {
        return capturedPageWrapper().getSqlSegment();
    }

    private AbstractWrapper<Target, ?, ?> capturedPageWrapper() {
        ArgumentCaptor<Wrapper<Target>> captor = wrapperCaptor();
        verify(targetMapper, atLeastOnce()).selectPage(any(), captor.capture());
        List<Wrapper<Target>> all = captor.getAllValues();
        // 条件构造器的取值是惰性求值的：先取 SQL 片段，参数表才会被填上
        AbstractWrapper<Target, ?, ?> wrapper = asWrapper(all.get(all.size() - 1));
        wrapper.getSqlSegment();
        return wrapper;
    }

    private Collection<Object> paramValues() {
        return capturedPageWrapper().getParamNameValuePairs().values();
    }

    /** 取口袋查询下发的参数值（同样要先物化 SQL 片段） */
    private Collection<Object> pocketQueryParamValues() {
        ArgumentCaptor<Wrapper<BindingPocket>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(bindingPocketMapper).selectList(captor.capture());
        AbstractWrapper<BindingPocket, ?, ?> wrapper = (AbstractWrapper<BindingPocket, ?, ?>) captor.getValue();
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<Target>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static AbstractWrapper<Target, ?, ?> asWrapper(Wrapper<Target> wrapper) {
        return (AbstractWrapper<Target, ?, ?>) wrapper;
    }

    private static Target target(long id) {
        Target target = new Target();
        target.setId(id);
        target.setUniprotId(id == TARGET_A ? "P00533" : "Q9BYF1");
        target.setName(id == TARGET_A ? "EGFR" : "ACE2");
        target.setChineseName(id == TARGET_A ? "表皮生长因子受体" : "血管紧张素转化酶 2");
        target.setGeneName(id == TARGET_A ? "EGFR" : "ACE2");
        target.setOrganism("human");
        target.setTargetType("受体酪氨酸激酶");
        target.setDiseaseArea("肿瘤");
        target.setStatus("supported");
        target.setSequence("MRPSGTAGAALLALLAALCPASRA");
        target.setSequenceLength(24);
        target.setDeleted(0);
        return target;
    }

    private static TargetStructure structure(long id, long targetId) {
        TargetStructure structure = new TargetStructure();
        structure.setId(id);
        structure.setTargetId(targetId);
        structure.setPdbId("1M17");
        structure.setChainId("A");
        structure.setSource("PDB");
        structure.setSourceKey("1M17:A");
        structure.setExperimentMethod("X_RAY");
        structure.setResolution(new BigDecimal("2.60"));
        structure.setLigandCount(1);
        structure.setPocketCount(2);
        structure.setIsPreferred(0);
        structure.setDeleted(0);
        return structure;
    }

    private static BindingPocket pocket(long id, long structureId, int pocketNo) {
        BindingPocket pocket = new BindingPocket();
        pocket.setId(id);
        pocket.setStructureId(structureId);
        pocket.setPocketNo(pocketNo);
        pocket.setMethod("P2RANK");
        pocket.setScore(new BigDecimal("12.3456"));
        pocket.setCenterX(new BigDecimal("1.000"));
        pocket.setCenterY(new BigDecimal("2.000"));
        pocket.setCenterZ(new BigDecimal("3.000"));
        pocket.setIsSelected(0);
        pocket.setDeleted(0);
        return pocket;
    }
}
