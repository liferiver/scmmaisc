package com.scmmaisc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.entity.Chapter;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.mapper.ChapterMapper;
import com.scmmaisc.mapper.ScenarioMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 场景数据装载器（R-06 / T011）：启动时扫描 {@code classpath:scenarios/*.json}，
 * 幂等装载 chapter 与 scenario（以 code / module_id 为键：存在则更新，否则插入）。
 *
 * <p>数据驱动（FR-002）：场景定义更新只需修改 JSON，无需改动平台代码。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScenarioDataLoader implements ApplicationRunner {

    private final ChapterMapper chapterMapper;
    private final ScenarioMapper scenarioMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:scenarios/*.json");
        if (resources.length == 0) {
            log.warn("未发现场景定义文件（resources/scenarios/*.json），跳过数据装载");
            return;
        }
        log.info("Scenarios loaded: {}", resources.length);
        int newChapters = 0;
        int newScenarios = 0;
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                ScenarioDef def = objectMapper.readValue(in, ScenarioDef.class);
                newChapters += upsertChapter(def.chapter());
                newScenarios += upsertScenario(def);
                log.info("场景装载: {} - {} ({})", def.moduleId(), def.name(), resource.getFilename());
            }
        }
        log.info("场景数据装载完成：新增 {} 章节、{} 场景（其余为更新）", newChapters, newScenarios);
    }

    /** 按 code 幂等装载章节；返回 1=新增，0=已存在并更新。 */
    private int upsertChapter(ChapterDef def) {
        Chapter existing = chapterMapper.selectOne(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getCode, def.code()));
        if (existing != null) {
            existing.setName(def.name());
            existing.setSortNo(def.sortNo());
            chapterMapper.updateById(existing);
            return 0;
        }
        Chapter chapter = new Chapter();
        chapter.setCode(def.code());
        chapter.setName(def.name());
        chapter.setSortNo(def.sortNo());
        chapterMapper.insert(chapter);
        return 1;
    }

    /** 按 module_id 幂等装载场景；返回 1=新增，0=已存在并更新。 */
    private int upsertScenario(ScenarioDef def) {
        Chapter chapter = chapterMapper.selectOne(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getCode, def.chapter().code()));
        if (chapter == null) {
            throw new IllegalStateException("场景 " + def.moduleId() + " 所属章节不存在: " + def.chapter().code());
        }
        Scenario existing = scenarioMapper.selectOne(
                new LambdaQueryWrapper<Scenario>().eq(Scenario::getModuleId, def.moduleId()));
        if (existing != null) {
            existing.setChapterId(chapter.getId());
            existing.setName(def.name());
            existing.setEngineKey(def.engineKey());
            existing.setDifficulty(def.difficulty());
            existing.setClassHours(def.classHours());
            existing.setIsRolePlay(def.isRolePlay());
            existing.setConcept(def.concept());
            existing.setDescription(def.description());
            existing.setDeps(toJson(def.deps()));
            existing.setParams(toJson(def.params()));
            existing.setOutputs(toJson(def.outputs()));
            existing.setConstraints(toJson(def.constraints()));
            scenarioMapper.updateById(existing);
            return 0;
        }
        Scenario scenario = new Scenario();
        scenario.setChapterId(chapter.getId());
        scenario.setModuleId(def.moduleId());
        scenario.setName(def.name());
        scenario.setEngineKey(def.engineKey());
        scenario.setDifficulty(def.difficulty());
        scenario.setClassHours(def.classHours());
        scenario.setIsRolePlay(def.isRolePlay());
        scenario.setConcept(def.concept());
        scenario.setDescription(def.description());
        scenario.setDeps(toJson(def.deps()));
        scenario.setParams(toJson(def.params()));
        scenario.setOutputs(toJson(def.outputs()));
        scenario.setConstraints(toJson(def.constraints()));
        scenarioMapper.insert(scenario);
        return 1;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 场景定义文件结构（与 data-model.md scenario 表一一对应）。 */
    public record ScenarioDef(
            ChapterDef chapter,
            String moduleId,
            String name,
            String engineKey,
            String difficulty,
            Integer classHours,
            Boolean isRolePlay,
            String concept,
            String description,
            List<String> deps,
            List<Map<String, Object>> params,
            List<Map<String, Object>> outputs,
            List<Map<String, Object>> constraints) {
    }

    /** 章节定义（JSON 内嵌，按 code 幂等装载）。 */
    public record ChapterDef(String code, String name, Integer sortNo) {
    }
}
