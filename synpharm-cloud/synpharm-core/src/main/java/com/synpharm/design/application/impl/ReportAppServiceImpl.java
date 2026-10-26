package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.AcceptedReportResponse;
import com.synpharm.design.api.dto.ReportCreateRequest;
import com.synpharm.design.api.dto.ReportDecisionRequest;
import com.synpharm.design.api.dto.ReportResponse;
import com.synpharm.design.application.JobAppService;
import com.synpharm.design.application.ReportAppService;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.DesignReport;
import com.synpharm.design.domain.model.JobType;
import com.synpharm.design.domain.model.ReportDecision;
import com.synpharm.design.domain.model.ReportStatus;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.DesignReportMapper;
import com.synpharm.design.support.JobPublisher;
import com.synpharm.design.support.SerialNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 报告与审批实现。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportAppServiceImpl implements ReportAppService {

    private static final Set<String> REPORT_TYPES = Set.of("TOP_N", "ROUND_SUMMARY", "ADHOC");

    /**
     * 能力边界声明（红线 R1/R2）。
     *
     * <p>报告必须自带「哪些东西没算」的说明，否则读者会默认所有指标都算过了。
     */
    private static final String CAPABILITY_NOTE_DEFERRED =
            "能力边界：报告正文由计算层消费 design.report 装配，当前计算层未部署，正文不会产出；"
                    + "候选与评估数据为空，本报告不含任何指标结论或排序；realness 分布：无。";

    private final DesignReportMapper reportMapper;
    private final DesignProjectMapper projectMapper;
    private final JsonCodec jsonCodec;
    private final JobAppService jobAppService;
    private final JobPublisher jobPublisher;

    @Override
    public PageResult<ReportResponse> list(Long projectId, String decision, int page, int pageSize) {
        Long userId = CurrentUser.requireUserId();
        ReportDecision parsed = StringUtils.hasText(decision) ? ReportDecision.parse(decision) : null;

        var result = reportMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, pageSize),
                Wrappers.lambdaQuery(DesignReport.class)
                        .eq(DesignReport::getUserId, userId)
                        .eq(projectId != null, DesignReport::getProjectId, projectId)
                        .eq(parsed != null, DesignReport::getDecision, parsed == null ? null : parsed.name())
                        .orderByDesc(DesignReport::getId));
        return PageResult.of(result, this::toResponse);
    }

    @Override
    public ReportResponse get(Long id) {
        return toResponse(loadOwned(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AcceptedReportResponse create(ReportCreateRequest request, String idempotencyKey) {
        Long userId = CurrentUser.requireUserId();
        if (!REPORT_TYPES.contains(request.getReportType())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "未知的 reportType: " + request.getReportType(), Map.of("allowedTypes", REPORT_TYPES));
        }
        DesignProject project = projectMapper.selectById(request.getProjectId());
        OwnershipGuard.assertOwned(userId, project == null ? null : project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, Map.of("projectId", request.getProjectId()));

        Long roundId = request.getRoundId() == null ? project.getCurrentRoundId() : request.getRoundId();
        int topN = request.getTopN() == null ? 10 : request.getTopN();
        // @Min(1) 只在 Controller 的 @Valid 生效；服务层再兜一道，避免将来被内部调用绕过校验写坏数据
        if (topN < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "topN 至少为 1", Map.of("topN", topN));
        }
        // 契约把这两个开关的默认值定为 true。这里必须**在服务端把默认值定下来再下发**：
        // 若原样透传 null，计算层看到的是"未指定"，很可能按 false 处理 —— 恰好与契约默认相反。
        boolean includeLineage = request.getIncludeLineage() == null || request.getIncludeLineage();
        boolean includeExperimentAccuracy =
                request.getIncludeExperimentAccuracy() == null || request.getIncludeExperimentAccuracy();

        // 幂等必须**先于**写业务行：否则重放会先插一条重复报告，再发现任务已存在 —— 报告已经重了。
        // 同键未终态 -> createOrReuse 抛 409（details 带 existingId）；已终态 -> 复用同一任务。
        boolean[] reused = new boolean[1];
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("projectId", project.getId());
        params.put("reportType", request.getReportType());
        params.put("topN", topN);
        params.put("includeLineage", includeLineage);
        params.put("includeExperimentAccuracy", includeExperimentAccuracy);
        if (request.getEvaluationRunId() != null) {
            // params_json 的用途是「任务可重放入参快照」（红线 R5），缺一项就无法只凭任务行复现请求
            params.put("evaluationRunId", request.getEvaluationRunId());
        }
        JobAppService.CreatedJob job = jobAppService.createOrReuse(JobType.REPORT, project.getId(), roundId,
                null, "REPORT", null, params, idempotencyKey, reused);
        if (reused[0]) {
            DesignReport previous = job.job().getBizId() == null
                    ? null : reportMapper.selectById(job.job().getBizId());
            if (previous != null) {
                log.info("Idempotency-Key 命中已终态任务 {}，复用报告 {}", job.no(), previous.getId());
                return accepted(previous, job);
            }
            // 复用到的已终态任务找不到关联报告：**不能**把新报告挂到这个终态任务上。
            // 消费端约定「终态任务不再执行」（规范 §7），那样新报告会永远停在 PENDING，
            // 而客户端轮询同一个 jobId 只会看到 SUCCEEDED 却没有正文 —— 状态自相矛盾。
            // 这本身是不该出现的状态（REPORT 任务必然关联报告），如实报冲突让人来查。
            throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                    "该 Idempotency-Key 已被一个已终态任务占用，但找不到其关联报告；请换用新的幂等键或人工核查",
                    Map.of("existingId", job.id(), "existingJobNo", job.no(),
                            "existingStatus", job.job().getStatus()));
        }

        DesignReport report = new DesignReport();
        report.setReportNo(SerialNumbers.reportNo());
        report.setProjectId(project.getId());
        report.setRoundId(roundId);
        report.setEvaluationRunId(request.getEvaluationRunId());
        report.setUserId(userId);
        report.setTitle(project.getName() + " · " + request.getReportType() + " 报告");
        report.setReportType(request.getReportType());
        report.setTopN(topN);
        report.setCapabilityNote(CAPABILITY_NOTE_DEFERRED);
        report.setDecision(ReportDecision.PENDING.name());
        report.setVersionNo(1);
        report.setStatus(ReportStatus.PENDING.name());
        reportMapper.insert(report);

        jobAppService.attachBusiness(job.id(), "REPORT", report.getId());
        report.setJobId(job.id());
        reportMapper.updateById(report);

        // 正文由计算层消费 design.report 装配（规范 §7）；消息一律事务提交后投（红线 R5）
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("reportId", report.getId());
        message.put("projectId", project.getId());
        message.put("topN", topN);
        message.put("includeLineage", includeLineage);
        message.put("includeExperimentAccuracy", includeExperimentAccuracy);
        if (request.getEvaluationRunId() != null) {
            message.put("evaluationRunId", request.getEvaluationRunId());
        }
        jobPublisher.publishAfterCommit(job.id(), job.no(), JobType.REPORT, message);
        log.info("报告 {} 已登记并投递任务 {}（项目 {}）", report.getId(), job.no(), project.getId());
        return accepted(report, job);
    }

    private AcceptedReportResponse accepted(DesignReport report, JobAppService.CreatedJob job) {
        AcceptedReportResponse response = new AcceptedReportResponse();
        response.setReportId(report.getId());
        response.setReportNo(report.getReportNo());
        response.setJobId(job.id());
        response.setStatus(report.getStatus());
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDecisionRequest.Response decide(Long id, ReportDecisionRequest request) {
        DesignReport report = loadOwned(id);
        ReportDecision decision = ReportDecision.parseDecidable(request.getDecision());

        if (decision.isDecided() && ReportDecision.parse(report.getDecision()).isDecided()) {
            throw new BizException(ErrorCode.REPORT_ALREADY_DECIDED, null,
                    Map.of("id", id, "decision", report.getDecision(), "decisionAt", String.valueOf(report.getDecisionAt())));
        }
        if (!ReportStatus.isDecidable(report.getStatus())) {
            throw new BizException(ErrorCode.REPORT_NOT_READY, null,
                    Map.of("id", id, "status", report.getStatus(), "capabilityNote", report.getCapabilityNote()));
        }
        if (decision.requiresNote() && !StringUtils.hasText(request.getNote())) {
            throw new BizException(ErrorCode.REPORT_DECISION_NOTE_REQUIRED, null,
                    Map.of("id", id, "decision", decision.name()));
        }

        report.setDecision(decision.name());
        report.setDecisionBy(CurrentUser.requireUserId());
        report.setDecisionAt(LocalDateTime.now());
        report.setDecisionNote(request.getNote());
        reportMapper.updateById(report);

        Long revisionId = null;
        if (decision == ReportDecision.REVISE) {
            revisionId = insertRevisionPlaceholder(report);
        }

        ReportDecisionRequest.Response response = new ReportDecisionRequest.Response();
        response.setId(report.getId());
        response.setDecision(report.getDecision());
        response.setDecisionBy(report.getDecisionBy());
        response.setDecisionAt(report.getDecisionAt());
        response.setDecisionNote(report.getDecisionNote());
        response.setVersionNo(report.getVersionNo());
        response.setRevisionReportId(revisionId);
        log.info("报告 {} 审批结论 {}（修订版 {}）", id, decision, revisionId);
        return response;
    }

    @Override
    public ExportPayload export(Long id, String format) {
        DesignReport report = loadOwned(id);
        String normalized = StringUtils.hasText(format) ? format.trim().toLowerCase() : "markdown";

        return switch (normalized) {
            case "markdown", "md" -> {
                requireBody(report, report.getBodyMd());
                yield new ExportPayload("text/markdown; charset=utf-8", report.getReportNo() + ".md",
                        report.getBodyMd().getBytes(StandardCharsets.UTF_8));
            }
            case "json" -> {
                requireBody(report, report.getBodyJson());
                yield new ExportPayload("application/json; charset=utf-8", report.getReportNo() + ".json",
                        report.getBodyJson().getBytes(StandardCharsets.UTF_8));
            }
            case "pdf" -> throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "PDF 渲染尚未落地（需报告渲染器），请先用 markdown / json 导出",
                    Map.of("id", id, "format", normalized));
            default -> throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "未知的 format: " + format, Map.of("allowedFormats", Set.of("markdown", "json", "pdf")));
        };
    }

    // ------------------------------------------------------------------ 内部

    private DesignReport loadOwned(Long id) {
        DesignReport report = reportMapper.selectById(id);
        OwnershipGuard.assertOwned(CurrentUser.requireUserId(),
                report == null ? null : report.getUserId(),
                ErrorCode.REPORT_NOT_FOUND, Map.of("id", id));
        return report;
    }

    private void requireBody(DesignReport report, String body) {
        if (!StringUtils.hasText(body)) {
            throw new BizException(ErrorCode.REPORT_NOT_READY, "报告正文尚未生成，无法导出",
                    Map.of("id", report.getId(), "status", report.getStatus()));
        }
    }

    /** REVISE 后的修订版占位：正文同样待生成，版本 +1 并记录 revision_of */
    private Long insertRevisionPlaceholder(DesignReport source) {
        DesignReport revision = new DesignReport();
        revision.setReportNo(SerialNumbers.reportNo());
        revision.setProjectId(source.getProjectId());
        revision.setRoundId(source.getRoundId());
        revision.setEvaluationRunId(source.getEvaluationRunId());
        revision.setUserId(source.getUserId());
        revision.setTitle(source.getTitle());
        revision.setReportType(source.getReportType());
        revision.setTopN(source.getTopN());
        revision.setCapabilityNote(source.getCapabilityNote());
        revision.setDecision(ReportDecision.PENDING.name());
        revision.setRevisionOf(source.getId());
        revision.setVersionNo((source.getVersionNo() == null ? 1 : source.getVersionNo()) + 1);
        revision.setStatus(ReportStatus.PENDING.name());
        reportMapper.insert(revision);
        return revision.getId();
    }

    private ReportResponse toResponse(DesignReport entity) {
        ReportResponse response = new ReportResponse();
        response.setId(entity.getId());
        response.setReportNo(entity.getReportNo());
        response.setProjectId(entity.getProjectId());
        response.setRoundId(entity.getRoundId());
        response.setEvaluationRunId(entity.getEvaluationRunId());
        response.setTitle(entity.getTitle());
        response.setReportType(entity.getReportType());
        response.setTopN(entity.getTopN());
        response.setBodyJson(jsonCodec.readMap(entity.getBodyJson()));
        response.setBodyMd(entity.getBodyMd());
        response.setCapabilityNote(entity.getCapabilityNote());
        response.setDecision(entity.getDecision());
        response.setDecisionBy(entity.getDecisionBy());
        response.setDecisionAt(entity.getDecisionAt());
        response.setDecisionNote(entity.getDecisionNote());
        response.setRevisionOf(entity.getRevisionOf());
        response.setVersionNo(entity.getVersionNo());
        response.setStatus(entity.getStatus());
        response.setJobId(entity.getJobId());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }
}
