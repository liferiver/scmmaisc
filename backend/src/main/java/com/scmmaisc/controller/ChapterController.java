package com.scmmaisc.controller;

import com.scmmaisc.common.ApiResult;
import com.scmmaisc.service.ChapterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 章节接口（C1 GET /api/chapters）。
 */
@RestController
@RequestMapping("/api/chapters")
@RequiredArgsConstructor
public class ChapterController {

    private final ChapterService chapterService;

    @GetMapping
    public ApiResult<List<ChapterService.ChapterVO>> list() {
        return ApiResult.ok(chapterService.listAll());
    }
}
