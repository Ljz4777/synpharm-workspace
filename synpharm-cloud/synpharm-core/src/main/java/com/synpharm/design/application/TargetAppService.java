package com.synpharm.design.application;

import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.BindingPocketResponse;
import com.synpharm.design.api.dto.TargetResponse;

import java.util.List;

/**
 * 靶点库用例（契约 §target：`GET /targets`、`GET /targets/{id}`、
 * `GET /structures/{id}/pockets`）。
 *
 * <p>{@code target} / {@code target_structure} / {@code binding_pocket} 是**全平台共享的参考数据**
 * （与知识库同类，三张表都没有属主列），因此本组端点**不做归属校验**，但仍要求已登录
 * （网关已保证）。
 *
 * <p>本接口只含读路径。创建路径（`POST /targets` 的 UniProt 导入、结构绑定、口袋检测、
 * 自定义口袋中心、选定口袋）不在本次范围内，原因见方案 §11.4 第 5 组。
 *
 * @author SynPharm Team
 */
public interface TargetAppService {

    /**
     * 靶点库分页检索。
     *
     * @param keyword    匹配 uniprotId / name / chineseName / geneName，可空
     * @param targetType 靶点类型精确过滤，可空
     * @param diseaseArea 疾病领域精确过滤，可空
     * @param status     支持状态，可空；**非空且非法时抛 400**，不静默返回全量
     */
    PageResult<TargetResponse> list(String keyword, String targetType, String diseaseArea,
                                    String status, int page, int pageSize);

    /** 靶点详情（含结构与口袋）；不存在返回 404 */
    TargetResponse get(Long id);

    /** 某结构下的口袋列表；结构不存在返回 404 */
    List<BindingPocketResponse> listPockets(Long structureId);
}
