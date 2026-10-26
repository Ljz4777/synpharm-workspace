package com.synpharm.common.error;

/**
 * 统一错误码（全平台单一枚举）。
 *
 * <p>规范：[`docs/conventions/01-接口与错误码规范.md`] §2 与 §8.1。
 * <ul>
 *   <li>{@code errorCode}（枚举常量名）是业务分支的<b>唯一</b>依据；</li>
 *   <li>{@code code} = {@code httpStatus × 100 + seq}，聚合统计用，<b>不承载业务语义</b>；</li>
 *   <li>{@code seq} 按序号块分配，避免多服务并行开发时号段冲突。</li>
 * </ul>
 *
 * <pre>
 * 序号块  归属
 * 00~09   通用 / 框架 / 网关
 * 10~19   auth
 * 20~39   predict
 * 40~59   design
 * 60~69   asset
 * 70~79   platform
 * 80~89   agent（二期）
 * 90~99   保留
 * </pre>
 *
 * <p>各服务的号段随该服务落地逐步补齐；未登记的码不得凭空使用（总则 G11）。
 *
 * @author SynPharm Team
 */
public enum ErrorCode {

    // ================= 通用 / 框架 / 网关（块 00~09）=================
    VALIDATION_FAILED(400, 0, "参数校验失败"),
    MALFORMED_REQUEST(400, 1, "请求体格式错误或字段类型不符"),
    UNAUTHORIZED(401, 0, "未登录或登录已失效"),
    TOKEN_EXPIRED(401, 1, "登录已过期，请重新登录"),
    TOKEN_INVALID(401, 2, "登录凭据无效，请重新登录"),
    SIGNATURE_INVALID(401, 3, "登录凭据校验失败，请重新登录"),
    TOKEN_BLACKLISTED(401, 4, "登录凭据已注销，请重新登录"),
    FORBIDDEN(403, 0, "没有访问该资源的权限"),
    RESOURCE_NOT_FOUND(404, 0, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, 0, "请求方法不被支持"),
    STALE_OBJECT(409, 0, "数据已被他人修改，请刷新后重试"),
    BUSINESS_CONFLICT(409, 1, "当前状态不允许该操作"),
    PAYLOAD_TOO_LARGE(413, 0, "上传内容超过大小限制"),
    UNSUPPORTED_MEDIA_TYPE(415, 0, "不支持的内容类型"),
    UNPROCESSABLE(422, 0, "业务规则不允许该操作"),
    RATE_LIMITED(429, 0, "请求过于频繁，请稍后重试"),
    INTERNAL_ERROR(500, 0, "服务内部错误，请稍后重试"),
    DEPENDENCY_UNAVAILABLE(503, 0, "依赖的下游服务暂不可用，请稍后重试"),
    UPSTREAM_TIMEOUT(504, 0, "上游服务响应超时，请稍后重试"),

    // ================= auth（块 10~19）=================
    LOGIN_TYPE_UNSUPPORTED(400, 10, "不支持的登录方式"),
    CAPTCHA_ERROR(400, 11, "验证码错误或已过期"),
    PASSWORD_TOO_WEAK(400, 12, "密码强度不足"),
    PASSWORD_ERROR(401, 10, "邮箱或密码错误"),
    USER_DISABLED(403, 10, "用户已禁用"),
    USER_NOT_FOUND(404, 10, "用户不存在"),
    USER_DELETED(404, 11, "账户已注销"),
    USER_EXISTS(409, 10, "该邮箱已注册"),
    EMAIL_NOT_VERIFIED(409, 11, "邮箱未验证"),
    EMAIL_BOUND(409, 12, "邮箱已被其他账户绑定"),
    ACCOUNT_LOCKED(423, 10, "账户已被锁定，请稍后重试"),
    CAPTCHA_SEND_LIMIT(429, 10, "验证码发送过于频繁，请稍后重试"),
    EMAIL_SEND_FAILED(502, 10, "邮件发送失败，请稍后重试"),

    // ================= predict（块 20~39）=================
    // 规范 §4.3。「迁移自」列指的是单体 predict 域原先用的旧整数码 / 字符串码。
    INPUT_RESOLVE_FAILED(400, 20, "输入解析失败"),
    INVALID_SEQUENCE(400, 21, "蛋白质序列不合法"),
    SEQUENCE_TOO_SHORT(400, 22, "序列长度不足（至少 31 个残基）"),
    INVALID_SMILES(400, 23, "SMILES 表达式不合法"),
    CHAIN_NOT_FOUND(400, 24, "PDB 结构中不存在指定链"),
    DRUG_NOT_SUPPORTED(400, 25, "药物不在 DDI 支持范围内"),
    BATCH_FILE_INVALID(400, 26, "批量文件格式或表头错误"),
    ALGO_TYPE_UNSUPPORTED(400, 27, "不支持的算法类型"),
    TASK_NOT_FOUND(404, 20, "预测任务不存在或不属于当前用户"),
    RESULT_NOT_FOUND(404, 21, "预测结果不存在或不属于当前用户"),
    UNIPROT_NOT_FOUND(404, 22, "UniProt 条目不存在"),
    PDB_NOT_FOUND(404, 23, "PDB 结构不存在"),
    TASK_RUNNING(409, 20, "任务正在运行"),
    TASK_COMPLETED(409, 21, "任务已完成"),
    BATCH_PARTIAL(422, 20, "批量执行部分失败"),
    PREDICT_ERROR(500, 20, "预测失败，请稍后重试"),
    FASTAPI_UNAVAILABLE(503, 20, "算法引擎不可用"),
    MODEL_UNAVAILABLE(503, 21, "模型服务不可用"),
    ALGO_TIMEOUT(504, 20, "算法执行超时"),

    // ================= design（块 40~59）=================
    CONSTRAINT_RULE_INVALID(400, 40, "约束规则的 ruleType 与 params 不匹配"),
    GENERATION_NO_STRATEGY(400, 41, "未指定任何生成策略"),
    GENERATION_SEED_REQUIRED(400, 42, "所选生成策略需要种子，但未提供"),
    MODALITY_MISMATCH(400, 43, "分子形式与当前操作不匹配"),
    MOLECULE_EDIT_INVALID(400, 44, "编辑参数非法、结构不合法或 Sanitize 失败"),
    MOLECULE_EDIT_NO_CHANGE(400, 45, "编辑未产生任何变化"),
    EVALUATION_NO_METRIC(400, 46, "项目未启用任何评估指标"),
    EVALUATION_NO_CANDIDATE(400, 47, "项目内没有可评估的候选分子"),
    REPORT_DECISION_NOTE_REQUIRED(400, 48, "驳回或要求修改时必须填写审批意见"),
    EXPERIMENT_ASSAY_UNKNOWN(400, 49, "未知的检测类型，请先在知识库注册"),
    FEEDBACK_NO_SAMPLE(400, 50, "没有有效的实测样本可回灌"),
    LIBRARY_PARENT_INVALID(400, 51, "父级不存在或会造成环"),
    COMPARE_SAME_OBJECT(400, 52, "不能与自身对比"),
    NO_EDITABLE_FIELD(400, 53, "补丁为空，没有任何可编辑字段"),
    OVERRIDE_REASON_REQUIRED(400, 54, "人工调整必须填写理由（没有理由的调整无法追溯）"),
    EDIT_REASON_REQUIRED(400, 55, "手工编辑必须填写改动说明（版本树靠它解释这一版改了什么）"),
    COMMAND_FORBIDDEN(403, 40, "该命令对当前角色或项目状态不可用"),
    DESIGN_PROJECT_NOT_FOUND(404, 40, "项目不存在或不属于当前用户"),
    TARGET_NOT_FOUND(404, 41, "靶点不存在"),
    GENERATION_RUN_NOT_FOUND(404, 42, "生成任务不存在或不属于当前用户"),
    CANDIDATE_NOT_FOUND(404, 43, "候选分子不存在或不属于当前用户"),
    MOLECULE_NOT_FOUND(404, 44, "分子不存在或不属于当前用户"),
    EVALUATION_RUN_NOT_FOUND(404, 45, "评估任务不存在或不属于当前用户"),
    JOB_NOT_FOUND(404, 46, "任务不存在或不属于当前用户"),
    REPORT_NOT_FOUND(404, 47, "报告不存在或不属于当前用户"),
    EXPERIMENT_NOT_FOUND(404, 48, "实验记录不存在或不属于当前用户"),
    LIBRARY_ITEM_NOT_FOUND(404, 50, "知识项不存在"),
    COMMAND_NOT_FOUND(404, 51, "命令 ID 不存在"),
    DESIGN_FILE_NOT_FOUND(404, 52, "设计域文件引用不存在"),
    CONSTRAINT_SET_NOT_FOUND(404, 49, "约束集不存在或不属于当前用户"),
    ROUND_NOT_FOUND(404, 53, "轮次不存在或不属于当前用户"),
    STRUCTURE_NOT_FOUND(404, 54, "结构不存在"),
    DESIGN_PROJECT_NOT_READY(409, 40, "靶点或约束集尚未就绪"),
    DESIGN_PROJECT_STATUS_INVALID(409, 41, "项目当前状态不允许该操作"),
    DESIGN_ROUND_ALREADY_OPEN(409, 42, "该项目已有未关闭的轮次"),
    DESIGN_TARGET_DUPLICATED(409, 43, "同一靶点已绑定到该项目"),
    DESIGN_PRIMARY_TARGET_EXISTS(409, 44, "该项目已有主靶点"),
    TARGET_SEQUENCE_REQUIRED(409, 45, "靶点缺少序列，无法执行 DTI"),
    POCKET_NOT_SELECTED(409, 46, "该策略需要种子，但尚未选定口袋"),
    CONSTRAINT_SET_NOT_EDITABLE(409, 47, "非草稿状态的约束集不可编辑"),
    GENERATION_RUN_ALREADY_ACTIVE(409, 48, "同轮次已有运行中的生成任务"),
    CANDIDATE_DUPLICATED(409, 49, "该分子已存在"),
    MOLECULE_DUPLICATED(409, 50, "编辑产物已存在"),
    MOLECULE_LINEAGE_CYCLE(409, 51, "该编辑会造成分子谱系成环"),
    USE_CURSOR(409, 52, "偏移量过深，请改用键集分页"),
    JOB_NOT_CANCELLABLE(409, 53, "任务已进入终态，不可取消"),

    // ------------------------------------------------------------------ asset（块 60~69）
    // 规范 §4.5。文件资产服务（synpharm-asset）用这一段。
    FILE_TYPE_UNSUPPORTED(400, 60, "文件类型不受支持"),
    FILE_NOT_FOUND(404, 60, "文件资产不存在"),
    FILE_TOO_LARGE(413, 60, "文件超限（单文件 ≤ 100MB）"),
    FILE_HASH_MISMATCH(409, 60, "声明的 SHA256 与实际不符"),
    FILE_IN_USE(409, 61, "ref_count > 0，不可删除"),
    STORAGE_UNAVAILABLE(503, 60, "对象存储不可用"),

    // ------------------------------------------------------------------ platform（块 70~79）
    // 规范 §4.6。工具注册表与能力矩阵服务（synpharm-platform）用这一段。
    TOOL_NOT_FOUND(404, 70, "工具不存在"),
    TOOL_CODE_DUPLICATED(409, 70, "工具码重复"),
    CAPABILITY_STALE(409, 71, "能力矩阵过期，请先触发探测"),
    TOOL_PROBE_FAILED(502, 70, "能力探测失败"),
    REPORT_NOT_READY(409, 54, "报告尚未生成完成，不能审批"),
    REPORT_ALREADY_DECIDED(409, 55, "该版本已审批，不可重复决策"),
    COMMAND_UNAVAILABLE(409, 56, "命令存在但引擎不可用"),
    LIBRARY_CODE_DUPLICATED(409, 57, "同分类下已存在相同编码"),
    LIBRARY_BUILTIN_IMMUTABLE(409, 58, "内置项只允许修改启用状态、排序与备注"),
    LIBRARY_HAS_CHILDREN(409, 59, "存在子节点，不可删除"),
    DESIGN_RATE_LIMITED(429, 40, "操作过于频繁，请稍后重试"),
    POCKET_DETECT_FAILED(502, 40, "口袋检测的全部方法均失败"),

    // ------------------------------------------------------------------ 需求基线（阶段 B）
    // 06 文档 §3。基线快照是生成任务 S0 的唯一输入，八块不齐不得冻结。
    BASELINE_INCOMPLETE(409, 60, "基线八块不完整，无法冻结"),
    BASELINE_STALE(409, 61, "预检后项目已被修改，请重新预检"),
    BASELINE_ALREADY_FROZEN(409, 62, "该项目当前版本已冻结，如需变更请先升版本"),
    DECISION_EVIDENCE_MISSING(409, 65, "候选缺少证据包（需先评估冻结），不能进入决策"),
    DECISION_NO_CANDIDATE(409, 69, "没有可进入决策的候选"),
    DECISION_NOT_EDITABLE(409, 63, "决策不是草稿状态，不能再修改；要改请新建一版"),
    DECISION_BLOCKED_BY_VETO(409, 64, "该候选被 BLOCK 级否决或硬约束复核不通过，不能在本次决策里改为入选"),
    RISK_ACCEPTANCE_FORBIDDEN(409, 66, "「必须验证」的风险项不接受（只能补数据后转暂缓）"),
    REPLAY_MISMATCH(409, 67, "回放结果与记录不一致：输入或规则已变化，请新建一版决策"),
    NO_VIABLE_CANDIDATE(409, 68, "没有任何候选进入湿实验，无法确认"),
    DECISION_VETO_RULE_GLOBAL_IMMUTABLE(409, 71, "全局默认规则只读，请为特定项目新建规则"),
    BASELINE_NOT_FOUND(404, 55, "基线快照不存在"),
    PROJECT_METRIC_NOT_FOUND(404, 56, "项目指标不存在"),
    PROJECT_RISK_NOT_FOUND(404, 57, "项目风险点不存在"),
    DECISION_NOT_FOUND(404, 58, "决策记录不存在或不属于当前用户"),
    DECISION_VETO_RULE_NOT_FOUND(404, 59, "否决规则不存在"),
    RISK_ACCEPTANCE_NOT_FOUND(404, 60, "风险接受记录不存在"),
    ;

    private final int httpStatus;
    private final int seq;
    private final String defaultMessage;

    ErrorCode(int httpStatus, int seq, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.seq = seq;
        this.defaultMessage = defaultMessage;
    }

    /** 聚合码 = HTTP 状态码 × 100 + 序号 */
    public int getCode() {
        return httpStatus * 100 + seq;
    }

    /** HTTP 状态码 */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** 业务错误标识；常量名即 errorCode */
    public String getErrorCode() {
        return name();
    }

    /** 默认用户文案 */
    public String getMessage() {
        return defaultMessage;
    }
}
