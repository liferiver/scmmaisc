package com.scmmaisc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.common.BizException;
import com.scmmaisc.common.ErrorCode;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.mapper.ScenarioMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 场景服务（C2/C3）：JSON 列解析为结构化对象返回；moduleId 不存在抛 404。
 */
@Service
@RequiredArgsConstructor
public class ScenarioService {

    private final ScenarioMapper scenarioMapper;
    private final ObjectMapper objectMapper;

    /** 场景概要列表（C2）；chapterId 为空时返回全部场景。 */
    public List<ScenarioSummaryVO> listByChapter(Long chapterId) {
        LambdaQueryWrapper<Scenario> wrapper = new LambdaQueryWrapper<>();
        if (chapterId != null) {
            wrapper.eq(Scenario::getChapterId, chapterId);
        }
        wrapper.orderByAsc(Scenario::getModuleId);
        return scenarioMapper.selectList(wrapper).stream().map(this::toSummary).toList();
    }

    /** 场景完整定义（C3），不存在抛 404 + 40401。 */
    public ScenarioDetailVO getByModuleId(String moduleId) {
        Scenario scenario = scenarioMapper.selectOne(
                new LambdaQueryWrapper<Scenario>().eq(Scenario::getModuleId, moduleId));
        if (scenario == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + moduleId);
        }
        return toDetail(scenario);
    }

    private ScenarioSummaryVO toSummary(Scenario s) {
        return new ScenarioSummaryVO(s.getId(), s.getChapterId(), s.getModuleId(), s.getName(), s.getDifficulty(),
                s.getClassHours(), Boolean.TRUE.equals(s.getIsRolePlay()), parseStringList(s.getDeps()));
    }

    private ScenarioDetailVO toDetail(Scenario s) {
        return new ScenarioDetailVO(s.getId(), s.getModuleId(), s.getName(), s.getDifficulty(),
                s.getClassHours(), Boolean.TRUE.equals(s.getIsRolePlay()), parseStringList(s.getDeps()),
                s.getChapterId(), s.getEngineKey(), s.getConcept(), s.getDescription(),
                parseJsonList(s.getParams()), parseJsonList(s.getOutputs()),
                parseJsonList(s.getConstraints()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("场景 JSON 列解析失败: " + json, e);
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("场景 JSON 列解析失败: " + json, e);
        }
    }

    /** C2 响应体：场景概要（含 chapterId 供目录页分组，T018）。 */
    public record ScenarioSummaryVO(Long id, Long chapterId, String moduleId, String name, String difficulty,
                                    Integer classHours, Boolean isRolePlay, List<String> deps) {
    }

    /** C3 响应体：完整场景定义。 */
    public record ScenarioDetailVO(Long id, String moduleId, String name, String difficulty,
                                   Integer classHours, Boolean isRolePlay, List<String> deps,
                                   Long chapterId, String engineKey, String concept, String description,
                                   List<Map<String, Object>> params,
                                   List<Map<String, Object>> outputs,
                                   List<Map<String, Object>> constraints) {
    }
}
