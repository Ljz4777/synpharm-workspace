package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.design.api.dto.RoundResponse;
import com.synpharm.design.application.RoundAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设计域 · 轮次（关闭）。
 *
 * <p>开启第一轮与启动下一轮挂在项目路径下，见 {@link ProjectController}。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/rounds")
@RequiredArgsConstructor
@Tag(name = "设计域 · 轮次", description = "轮次关闭与结论")
public class RoundController {

    private final RoundAppService roundAppService;

    @PostMapping("/{id}/close")
    @Operation(summary = "关闭轮次并写结论", description = """
            轮次不存在或非本人返回 ROUND_NOT_FOUND(40453)；
            已关闭返回 BUSINESS_CONFLICT(40901)。""")
    public Result<RoundResponse> close(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        String conclusionText = body == null ? null : (String) body.get("conclusionText");
        return Result.success(roundAppService.close(id, conclusionText));
    }
}
