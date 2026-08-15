package com.scmmaisc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scmmaisc.entity.Chapter;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.mapper.ChapterMapper;
import com.scmmaisc.mapper.ScenarioMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 章节服务（C1）：按 sort_no 排序返回章节，并聚合各章场景数。
 */
@Service
@RequiredArgsConstructor
public class ChapterService {

    private final ChapterMapper chapterMapper;
    private final ScenarioMapper scenarioMapper;

    public List<ChapterVO> listAll() {
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().orderByAsc(Chapter::getSortNo));
        Map<Long, Long> counts = scenarioMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(Scenario::getChapterId, Collectors.counting()));
        return chapters.stream()
                .map(c -> new ChapterVO(c.getId(), c.getCode(), c.getName(), c.getSortNo(),
                        counts.getOrDefault(c.getId(), 0L).intValue()))
                .toList();
    }

    /** C1 响应体：章节 + 场景数聚合。 */
    public record ChapterVO(Long id, String code, String name, Integer sortNo, Integer scenarioCount) {
    }
}
