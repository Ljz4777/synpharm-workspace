package com.synpharm.design.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建项目响应（契约 {@code ProjectCreatedResponse}）。
 *
 * @author SynPharm Team
 */
@Data
public class ProjectCreatedResponse {

    private Long id;

    private String projectNo;

    private String status;

    /** 进入 READY 还缺什么；为空表示创建即就绪 */
    private List<String> readyBlockers = new ArrayList<>();

    public static ProjectCreatedResponse of(ProjectResponse project) {
        ProjectCreatedResponse response = new ProjectCreatedResponse();
        response.setId(project.getId());
        response.setProjectNo(project.getProjectNo());
        response.setStatus(project.getStatus());
        response.setReadyBlockers(project.getReadyBlockers());
        return response;
    }
}
