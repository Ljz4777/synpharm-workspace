package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUserTestSupport;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.LibraryItemNodeResponse;
import com.synpharm.design.api.dto.LibraryItemResponse;
import com.synpharm.design.api.dto.LibraryItemUpsertRequest;
import com.synpharm.design.domain.model.DesignLibraryItem;
import com.synpharm.design.infrastructure.persistence.DesignLibraryItemMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库用例测试。
 *
 * <p>锁死四类容易写错、又不会在「顺手点一下」时暴露的地方：
 * 物化路径与层级的推导、内置项的可改字段白名单、换父成环检测、删父级时子节点仍在。
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
class LibraryAppServiceImplTest {

    private static final long ME = 100L;

    @Mock
    private DesignLibraryItemMapper mapper;

    private LibraryAppServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LibraryAppServiceImpl(mapper, new JsonCodec(new ObjectMapper()));
        // 三个读入口都要求已登录（问题总账 H-27）：不预置身份，所有 list/tree/get 用例都会 401
        CurrentUserTestSupport.login(ME);
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.logout();
    }

    @Test
    @DisplayName("无身份：三个读入口全部 401，且不查库（不靠网关兜底，服务自己 fail-closed）")
    void rejectsAnonymousReaders() {
        CurrentUserTestSupport.logout();

        assertThatThrownBy(() -> service.list("MODALITY", null, null, null, 1, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> service.tree("MODALITY", true))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> service.get(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(mapper, never()).selectPage(any(), any());
        verify(mapper, never()).selectList(any());
        verify(mapper, never()).selectById(any());
    }

    private static DesignLibraryItem item(Long id, String category, String code, Long parentId,
                                          Integer levelNo, String path, Integer builtin) {
        DesignLibraryItem e = new DesignLibraryItem();
        e.setId(id);
        e.setCategory(category);
        e.setCode(code);
        e.setName(code);
        e.setParentId(parentId);
        e.setLevelNo(levelNo);
        e.setPath(path);
        e.setEnabled(1);
        e.setBuiltin(builtin);
        e.setSortNo(0);
        return e;
    }

    private static LibraryItemUpsertRequest request(String category, String code, Long parentId) {
        LibraryItemUpsertRequest r = new LibraryItemUpsertRequest();
        r.setCategory(category);
        r.setCode(code);
        r.setName(code);
        r.setParentId(parentId);
        return r;
    }

    @Test
    @DisplayName("新建根节点：levelNo=1，path=/分类/编码")
    void createRootDerivesLevelAndPath() {
        when(mapper.selectCount(any())).thenReturn(0L);

        service.create(request("ASSAY", "IC50", null));

        ArgumentCaptor<DesignLibraryItem> captor = ArgumentCaptor.forClass(DesignLibraryItem.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getLevelNo()).isEqualTo(1);
        assertThat(captor.getValue().getPath()).isEqualTo("/ASSAY/IC50");
        assertThat(captor.getValue().getEnabled()).isEqualTo(1);
        assertThat(captor.getValue().getBuiltin()).isEqualTo(0);
    }

    @Test
    @DisplayName("新建子节点：levelNo 与 path 都挂到父级下")
    void createChildDerivesFromParent() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectById(10L)).thenReturn(item(10L, "DISEASE", "ONCOLOGY", null, 1, "/DISEASE/ONCOLOGY", 1));

        service.create(request("DISEASE", "NSCLC", 10L));

        ArgumentCaptor<DesignLibraryItem> captor = ArgumentCaptor.forClass(DesignLibraryItem.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getLevelNo()).isEqualTo(2);
        assertThat(captor.getValue().getPath()).isEqualTo("/DISEASE/ONCOLOGY/NSCLC");
        assertThat(captor.getValue().getParentId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("同分类 code 重复：409 LIBRARY_CODE_DUPLICATED")
    void createRejectsDuplicatedCode() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("ASSAY", "IC50", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_CODE_DUPLICATED);
        verify(mapper, never()).insert(any(DesignLibraryItem.class));
    }

    @Test
    @DisplayName("父级不存在：400 LIBRARY_PARENT_INVALID")
    void createRejectsMissingParent() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(request("DISEASE", "NSCLC", 99L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_PARENT_INVALID);
    }

    @Test
    @DisplayName("父级跨分类：400 LIBRARY_PARENT_INVALID")
    void createRejectsParentOfAnotherCategory() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectById(10L)).thenReturn(item(10L, "ASSAY", "IC50", null, 1, "/ASSAY/IC50", 1));

        assertThatThrownBy(() -> service.create(request("DISEASE", "NSCLC", 10L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_PARENT_INVALID);
    }

    @Test
    @DisplayName("换父到自身子树内：400 LIBRARY_PARENT_INVALID（成环）")
    void updateRejectsCycle() {
        DesignLibraryItem self = item(1L, "DISEASE", "ONCOLOGY", null, 1, "/DISEASE/ONCOLOGY", 0);
        DesignLibraryItem descendant = item(2L, "DISEASE", "NSCLC", 1L, 2, "/DISEASE/ONCOLOGY/NSCLC", 0);
        when(mapper.selectById(1L)).thenReturn(self);
        when(mapper.selectById(2L)).thenReturn(descendant);
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.update(1L, request("DISEASE", "ONCOLOGY", 2L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_PARENT_INVALID);
        verify(mapper, never()).updateById(any(DesignLibraryItem.class));
    }

    @Test
    @DisplayName("内置项改 name：409 LIBRARY_BUILTIN_IMMUTABLE")
    void updateRejectsBuiltinStructuralChange() {
        DesignLibraryItem builtin = item(1L, "ASSAY", "IC50", null, 1, "/ASSAY/IC50", 1);
        builtin.setName("半数抑制浓度");
        when(mapper.selectById(1L)).thenReturn(builtin);

        LibraryItemUpsertRequest req = request("ASSAY", "IC50", null);
        req.setName("改个名字");

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_BUILTIN_IMMUTABLE);
        verify(mapper, never()).updateById(any(DesignLibraryItem.class));
    }

    @Test
    @DisplayName("内置项改 enabled/sortNo/remark：放行")
    void updateAllowsBuiltinOperationalFields() {
        DesignLibraryItem builtin = item(1L, "ASSAY", "IC50", null, 1, "/ASSAY/IC50", 1);
        builtin.setName("半数抑制浓度");
        when(mapper.selectById(1L)).thenReturn(builtin);

        LibraryItemUpsertRequest req = request("ASSAY", "IC50", null);
        req.setName("半数抑制浓度");
        req.setEnabled(false);
        req.setSortNo(9);
        req.setRemark("暂停使用");

        LibraryItemResponse response = service.update(1L, req);

        assertThat(response.getEnabled()).isFalse();
        assertThat(response.getSortNo()).isEqualTo(9);
        assertThat(response.getRemark()).isEqualTo("暂停使用");
        verify(mapper).updateById(any(DesignLibraryItem.class));
    }

    @Test
    @DisplayName("换父导致路径变化：子树整体平移")
    void updatePropagatesSubtreePath() {
        DesignLibraryItem self = item(2L, "DISEASE", "NSCLC", 1L, 2, "/DISEASE/ONCOLOGY/NSCLC", 0);
        DesignLibraryItem newParent = item(3L, "DISEASE", "RESPIRATORY", null, 1, "/DISEASE/RESPIRATORY", 0);
        when(mapper.selectById(2L)).thenReturn(self);
        when(mapper.selectById(3L)).thenReturn(newParent);
        when(mapper.selectCount(any())).thenReturn(0L);

        service.update(2L, request("DISEASE", "NSCLC", 3L));

        ArgumentCaptor<DesignLibraryItem> captor = ArgumentCaptor.forClass(DesignLibraryItem.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getPath()).isEqualTo("/DISEASE/RESPIRATORY/NSCLC");
        assertThat(captor.getValue().getLevelNo()).isEqualTo(2);

        verify(mapper).updateSubtreePath(eq("DISEASE"), eq("/DISEASE/ONCOLOGY/NSCLC"),
                eq("/DISEASE/ONCOLOGY/NSCLC"), eq("/DISEASE/RESPIRATORY/NSCLC"), eq(0));
    }

    @Test
    @DisplayName("路径含下划线：转义值只喂给 LIKE，长度计算必须用原始路径")
    void updateSubtreePassesRawPathForArithmetic() {
        // 回归用例。曾经把「转义后」的路径同时用于 LIKE 与 LENGTH/SUBSTRING：
        // 路径里每个 _ 转义后多一个反斜杠，LENGTH 随之偏大，SUBSTRING 多切几个字符，
        // 子节点路径被截断成 .../E2E_B_8413_C_1631 这种非法值。
        DesignLibraryItem self = item(2L, "ASSAY", "KD_SPR", 1L, 2, "/ASSAY/PARENT_1/KD_SPR", 0);
        DesignLibraryItem newParent = item(3L, "ASSAY", "NEW_PARENT", null, 1, "/ASSAY/NEW_PARENT", 0);
        when(mapper.selectById(2L)).thenReturn(self);
        when(mapper.selectById(3L)).thenReturn(newParent);
        when(mapper.selectCount(any())).thenReturn(0L);

        service.update(2L, request("ASSAY", "KD_SPR", 3L));

        verify(mapper).updateSubtreePath(
                eq("ASSAY"),
                eq("/ASSAY/PARENT_1/KD_SPR"),
                eq("/ASSAY/PARENT\\_1/KD\\_SPR"),
                eq("/ASSAY/NEW_PARENT/KD_SPR"),
                eq(0));
    }

    @Test
    @DisplayName("更新时省略 metaJson/sortNo/enabled：保持原值，不清空")
    void updateKeepsOmittedOptionalFields() {
        DesignLibraryItem entity = item(1L, "ASSAY", "IC50", null, 1, "/ASSAY/IC50", 0);
        entity.setMetaJson("{\"unit\":\"nM\"}");
        entity.setSortNo(7);
        entity.setEnabled(1);
        when(mapper.selectById(1L)).thenReturn(entity);
        when(mapper.selectCount(any())).thenReturn(0L);

        LibraryItemUpsertRequest req = request("ASSAY", "IC50", null);

        LibraryItemResponse response = service.update(1L, req);

        assertThat(response.getMeta()).isEqualTo(Map.of("unit", "nM"));
        assertThat(response.getSortNo()).isEqualTo(7);
        assertThat(response.getEnabled()).isTrue();
    }

    @Test
    @DisplayName("未换父：不触发子树平移")
    void updateWithoutMoveSkipsSubtreeUpdate() {
        DesignLibraryItem self = item(1L, "ASSAY", "IC50", null, 1, "/ASSAY/IC50", 0);
        when(mapper.selectById(1L)).thenReturn(self);
        when(mapper.selectCount(any())).thenReturn(0L);

        service.update(1L, request("ASSAY", "IC50", null));

        verify(mapper, never()).updateSubtreePath(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("有子节点时不可删除：409 LIBRARY_HAS_CHILDREN")
    void deleteRejectsWhenChildrenExist() {
        when(mapper.selectById(1L)).thenReturn(item(1L, "DISEASE", "ONCOLOGY", null, 1, "/DISEASE/ONCOLOGY", 0));
        when(mapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_HAS_CHILDREN);
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("无子节点：走逻辑删除 deleteById")
    void deleteSucceedsWhenLeaf() {
        when(mapper.selectById(1L)).thenReturn(item(1L, "ASSAY", "IC50", null, 1, "/ASSAY/IC50", 0));
        when(mapper.selectCount(any())).thenReturn(0L);

        service.delete(1L);

        verify(mapper).deleteById(1L);
    }

    @Test
    @DisplayName("不存在：404 LIBRARY_ITEM_NOT_FOUND")
    void getRejectsMissingItem() {
        when(mapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(7L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("树：父级被过滤掉时子项提升为根，不让子树凭空消失")
    void treePromotesOrphansToRoots() {
        DesignLibraryItem parent = item(1L, "DISEASE", "ONCOLOGY", null, 1, "/DISEASE/ONCOLOGY", 1);
        DesignLibraryItem child = item(2L, "DISEASE", "NSCLC", 1L, 2, "/DISEASE/ONCOLOGY/NSCLC", 1);
        DesignLibraryItem standalone = item(3L, "DISEASE", "CARDIO", null, 1, "/DISEASE/CARDIO", 1);
        // enabledOnly 只放回这两个（父节点被停用后不再出现在结果里）
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(child, standalone));

        List<LibraryItemNodeResponse> roots = service.tree("DISEASE", true);

        assertThat(roots).extracting(LibraryItemNodeResponse::getCode).containsExactly("NSCLC", "CARDIO");
        assertThat(roots).extracting(LibraryItemNodeResponse::getCode).doesNotContain(parent.getCode());
    }

    @Test
    @DisplayName("meta_json 反向序列化为 meta 对象")
    void responseDeserializesMetaJson() {
        DesignLibraryItem e = item(1L, "ASSAY", "IC50", null, 1, "/ASSAY/IC50", 1);
        e.setMetaJson("{\"unit\":\"nM\",\"higherBetter\":false}");
        when(mapper.selectById(1L)).thenReturn(e);

        LibraryItemResponse response = service.get(1L);

        assertThat(response.getMeta()).isEqualTo(Map.of("unit", "nM", "higherBetter", false));
        assertThat(response.getBuiltin()).isTrue();
    }
}
