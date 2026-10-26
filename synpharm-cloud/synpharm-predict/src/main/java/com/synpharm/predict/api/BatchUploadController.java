package com.synpharm.predict.api;

import com.synpharm.predict.api.dto.BatchStatusResponse;
import com.synpharm.predict.api.dto.BatchUploadResponse;
import com.synpharm.predict.application.BatchProcessService;
import com.synpharm.common.security.CurrentUser;import com.synpharm.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "批量预测", description = "批量CSV上传和预测接口")
public class BatchUploadController {

    private final BatchProcessService batchProcessService;

    @PostMapping("/upload")
    @Operation(summary = "批量CSV上传", description = "上传CSV文件进行批量预测")
    public Result<BatchUploadResponse> uploadBatch(
            @RequestParam("file") MultipartFile file,
            @RequestParam("algoType") String algoType) {
        Long userId = CurrentUser.requireUserId();
        return Result.success(batchProcessService.uploadBatch(file, algoType, userId));
    }

    @GetMapping("/status/{batchId}")
    @Operation(summary = "查询批量状态", description = "查询批量任务进度和状态")
    public Result<BatchStatusResponse> getBatchStatus(
            @PathVariable String batchId) {
        Long userId = CurrentUser.requireUserId();
        return Result.success(batchProcessService.getBatchStatus(batchId, userId));
    }

    @GetMapping("/download/{batchId}")
    @Operation(summary = "下载批量结果", description = "下载批量预测结果文件")
    public ResponseEntity<?> downloadBatch(
            @PathVariable String batchId) {
        Long userId = CurrentUser.requireUserId();
        return batchProcessService.downloadBatch(batchId, userId);
    }

    @GetMapping("/{batchId}/items")
    @Operation(summary = "查询批量任务明细", description = "分页查询批量任务每一行的处理状态（修复方案 5.6）")
    public Result<?> getBatchItems(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Integer status) {
        Long userId = CurrentUser.requireUserId();
        return Result.success(batchProcessService.getBatchItems(batchId, userId, page, pageSize, status));
    }
}
