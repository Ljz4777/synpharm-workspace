package com.synpharm.design.application;

import com.synpharm.design.api.dto.LeaderboardResponse;

/**
 * 排行榜用例（契约 {@code GET /api/design/projects/{id}/leaderboard}）。
 *
 * <p>两种分页：默认偏移分页；带 {@code afterScore}/{@code afterId} 时切换为键集分页
 * （规范 §4.2：排行榜在大表上 `OFFSET` 会退化，偏页 > 1000 直接返回 409 {@code USE_CURSOR}）。
 *
 * @author SynPharm Team
 */
public interface LeaderboardAppService {

    /**
     * 排行榜。
     *
     * @param projectId  项目 id（做归属校验）
     * @param roundId    轮次 id，**必填**（排行榜的主体是"某一轮的候选"）
     * @param sortBy     排序字段白名单：{@code total_score}（默认）或任一 metricCode
     * @param order      {@code asc} / {@code desc}（默认 desc）
     * @param afterScore 键集游标：上一页最后一条的排序值
     * @param afterId    键集游标：上一页最后一条的主键
     * @param page       偏移模式下的页码（1 起）
     * @param pageSize   每页条数
     */
    LeaderboardResponse leaderboard(Long projectId, Long roundId, String sortBy, String order,
                                    java.math.BigDecimal afterScore, Long afterId, int page, int pageSize);
}
