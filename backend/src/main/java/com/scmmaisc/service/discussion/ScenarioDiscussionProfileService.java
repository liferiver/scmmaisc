package com.scmmaisc.service.discussion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.entity.Chapter;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.entity.ScenarioDiscussionProfile;
import com.scmmaisc.mapper.ChapterMapper;
import com.scmmaisc.mapper.ScenarioDiscussionProfileMapper;
import com.scmmaisc.mapper.ScenarioMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景讨论配置服务（research.md §6，FR-006）：启动时对全部场景自动生成基础版
 * —— concept 按顿号/逗号拆分 → 概念标签；deps 场景 → 前序知识；章节 → 教材章节；
 * 后续延伸 → 全局知识链路映射表（代码内置，方案书 2.5 库存/协同/跨境三条链路）；
 * 案例库/理论库 → 空数组。`resources/discussion-profiles/*.json` 人工覆盖文件
 * （按 moduleId 匹配）整体替换自动生成结果（source=MANUAL）。
 * 幂等 upsert（module_id 存在则更新）；D8 查询与编排装载共用解析后视图 ProfileVO。
 * 启动顺序依赖 ScenarioDataLoader 先装载场景（@Order(2)）。
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class ScenarioDiscussionProfileService implements ApplicationRunner {

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";

    /** 全局知识链路映射（research.md §6，依据方案书 2.5 库存/协同/跨境三条链路）。 */
    static final List<List<String>> KNOWLEDGE_CHAINS = List.of(
            // 库存链路：经济批量 → 随机需求(s,Q) → 推拉策略 → 牛鞭效应 → 库存质押融资
            List.of("CH2-003", "CH2-006", "CH7-002", "CH8-001", "CH9-002"),
            // 协同链路：CPFR → 精益/敏捷 → 牛鞭效应 → 收益共享
            List.of("CH6-003", "CH7-003", "CH8-001", "CH8-006"),
            // 跨境链路：京东混合模式 → 跨境三段式 → 海外仓 → 全球风险 → IoT/AI
            List.of("CH4-004", "CH5-001", "CH5-007", "CH10-003", "CH11-009"));

    /** 未配置切入点的通用引导模板（零虚构，US5-AC3）。 */
    static final List<String> GENERIC_STARTERS = List.of(
            "结合本次运行结果，解读关键指标的变化趋势及其业务含义",
            "对比不同参数取值下的结果差异，分析背后的模型机制",
            "从你的角色视角，提出一个值得全体深入讨论的问题");

    /** 人工覆盖目录（classpath:discussion-profiles/*.json）。 */
    private static final String PROFILE_DIR = "discussion-profiles/";

    private final ScenarioMapper scenarioMapper;
    private final ChapterMapper chapterMapper;
    private final ScenarioDiscussionProfileMapper profileMapper;
    private final ObjectMapper objectMapper;

    /** 人工覆盖映射（moduleId → 文件内容；同名文件后加载覆盖先加载，测试资源优先）。 */
    private final Map<String, ManualProfile> manualOverrides = new HashMap<>();

    @Override
    public void run(ApplicationArguments args) {
        loadAll();
    }

    /** 装载全部场景配置：MANUAL 覆盖优先，否则 AUTO 生成；幂等 upsert；返回处理场景数。 */
    public int loadAll() {
        loadManualOverrides();
        List<Scenario> scenarios = scenarioMapper.selectList(null);
        for (Scenario scenario : scenarios) {
            upsert(scenario);
        }
        log.info("场景讨论配置装载完成：{} 个场景（人工覆盖 {} 份）", scenarios.size(), manualOverrides.size());
        return scenarios.size();
    }

    /** 当前配置行数（SC-001 覆盖断言）。 */
    public long count() {
        return profileMapper.selectCount(null);
    }

    /** D8：按 moduleId 查询（场景或配置不存在 → null，控制器映射 404）。 */
    public ProfileVO getByModuleId(String moduleId) {
        Scenario scenario = scenarioMapper.selectOne(
                new LambdaQueryWrapper<Scenario>().eq(Scenario::getModuleId, moduleId));
        if (scenario == null) {
            return null;
        }
        ScenarioDiscussionProfile p = profileMapper.selectOne(
                new LambdaQueryWrapper<ScenarioDiscussionProfile>()
                        .eq(ScenarioDiscussionProfile::getModuleId, moduleId));
        return p == null ? null : toVO(p);
    }

    /** 编排装载：按 scenarioId 取配置（缺失 → null，PromptBuilder 走通用模板，零虚构）。 */
    public ProfileVO findByScenarioId(Long scenarioId) {
        ScenarioDiscussionProfile p = profileMapper.selectOne(
                new LambdaQueryWrapper<ScenarioDiscussionProfile>()
                        .eq(ScenarioDiscussionProfile::getScenarioId, scenarioId));
        return p == null ? null : toVO(p);
    }

    // ---- 装载与生成 ----

    private void upsert(Scenario scenario) {
        ManualProfile manual = manualOverrides.get(scenario.getModuleId());
        ScenarioDiscussionProfile existing = profileMapper.selectOne(
                new LambdaQueryWrapper<ScenarioDiscussionProfile>()
                        .eq(ScenarioDiscussionProfile::getModuleId, scenario.getModuleId()));
        if (existing == null) {
            existing = new ScenarioDiscussionProfile();
            existing.setScenarioId(scenario.getId());
            existing.setModuleId(scenario.getModuleId());
        }
        // 先构建完整字段（整体替换语义），再统一 insert/update（避免 NOT NULL 字段先写空行）
        if (manual != null) {
            existing.setSource(SOURCE_MANUAL);
            existing.setConceptTags(toJson(manual.conceptTags()));
            existing.setChapterSection(manual.chapterSection());
            existing.setPrevKnowledge(toJson(manual.prevKnowledge()));
            existing.setNextExtension(toJson(manual.nextExtension()));
            existing.setDiscussionStarters(toJson(manual.discussionStarters()));
            existing.setCaseLibrary(toJson(manual.caseLibrary()));
            existing.setTheoryLibrary(toJson(manual.theoryLibrary()));
        } else {
            ProfileVO auto = autoGenerate(scenario);
            existing.setSource(SOURCE_AUTO);
            existing.setConceptTags(toJson(auto.conceptTags()));
            existing.setChapterSection(auto.chapterSection());
            existing.setPrevKnowledge(toJson(auto.prevKnowledge()));
            existing.setNextExtension(toJson(auto.nextExtension()));
            existing.setDiscussionStarters(toJson(auto.discussionStarters()));
            existing.setCaseLibrary(toJson(auto.caseLibrary()));
            existing.setTheoryLibrary(toJson(auto.theoryLibrary()));
        }
        existing.setUpdatedAt(LocalDateTime.now());
        if (existing.getId() == null) {
            profileMapper.insert(existing);
        } else {
            profileMapper.updateById(existing);
        }
    }

    /** AUTO 生成基础版：concept 拆分、deps→前序、链路→后续、通用切入点、空案例/理论库。 */
    private ProfileVO autoGenerate(Scenario scenario) {
        List<String> prev = new ArrayList<>(depNames(scenario.getDeps()));
        List<String> next = new ArrayList<>();
        for (List<String> chain : KNOWLEDGE_CHAINS) {
            int idx = chain.indexOf(scenario.getModuleId());
            if (idx < 0) {
                continue;
            }
            // 链路内前序/后续知识点（按场景名引用，数据驱动零虚构）
            for (int i = 0; i < idx; i++) {
                prev.add(nameOf(chain.get(i)));
            }
            for (int i = idx + 1; i < chain.size(); i++) {
                next.add(nameOf(chain.get(i)));
            }
            break;
        }
        Chapter chapter = scenario.getChapterId() == null ? null : chapterMapper.selectById(scenario.getChapterId());
        return new ProfileVO(scenario.getModuleId(), splitConcept(scenario.getConcept()),
                chapter == null ? null : chapter.getName(), dedupe(prev), dedupe(next),
                GENERIC_STARTERS, List.of(), List.of(), SOURCE_AUTO);
    }

    /** 扫描人工覆盖文件；同名 moduleId 后加载覆盖先加载（测试类路径位于主类路径之后，测试覆盖生效）。 */
    private void loadManualOverrides() {
        manualOverrides.clear();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:" + PROFILE_DIR + "*.json");
            List<Resource> ordered = Arrays.stream(resources)
                    .sorted(Comparator.comparing(r -> {
                        try {
                            return r.getURL().toString();
                        } catch (IOException e) {
                            return r.getFilename();
                        }
                    }))
                    .toList();
            for (Resource resource : ordered) {
                try (InputStream in = resource.getInputStream()) {
                    ManualProfile def = objectMapper.readValue(in, ManualProfile.class);
                    if (def.moduleId() == null || def.moduleId().isBlank()) {
                        log.warn("讨论配置人工覆盖文件缺少 moduleId: {}", resource.getFilename());
                        continue;
                    }
                    manualOverrides.put(def.moduleId(), def);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("讨论配置人工覆盖文件扫描失败", e);
        }
    }

    /** concept 按顿号/逗号拆分 → 概念标签（去空去重）。 */
    private static List<String> splitConcept(String concept) {
        if (concept == null || concept.isBlank()) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String part : concept.split("[、,，;；]")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                tags.add(t);
            }
        }
        return List.copyOf(tags);
    }

    /** deps 模块 ID 数组 → 场景名列表（数据驱动）。 */
    private List<String> depNames(String depsJson) {
        if (depsJson == null || depsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> ids = objectMapper.readValue(depsJson, new TypeReference<List<String>>() {
            });
            List<String> names = new ArrayList<>();
            for (String id : ids) {
                String name = nameOf(id);
                if (name != null) {
                    names.add(name);
                }
            }
            return names;
        } catch (IOException e) {
            throw new IllegalStateException("场景 deps 解析失败", e);
        }
    }

    private String nameOf(String moduleId) {
        Scenario s = scenarioMapper.selectOne(
                new LambdaQueryWrapper<Scenario>().eq(Scenario::getModuleId, moduleId));
        return s == null ? null : s.getName();
    }

    /** 保序去重（deps 与链路前序可能重叠）。 */
    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private ProfileVO toVO(ScenarioDiscussionProfile p) {
        return new ProfileVO(p.getModuleId(), parseList(p.getConceptTags()), p.getChapterSection(),
                parseList(p.getPrevKnowledge()), parseList(p.getNextExtension()),
                parseList(p.getDiscussionStarters()), parseList(p.getCaseLibrary()),
                parseList(p.getTheoryLibrary()), p.getSource());
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("讨论配置数组解析失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("讨论配置序列化失败", e);
        }
    }

    /** D8 / 编排共用视图（解析后的列表）。 */
    public record ProfileVO(String moduleId, List<String> conceptTags, String chapterSection,
                            List<String> prevKnowledge, List<String> nextExtension,
                            List<String> discussionStarters, List<String> caseLibrary,
                            List<String> theoryLibrary, String source) {
    }

    /** 人工覆盖文件结构（字段缺失按整体替换语义置空）。 */
    public record ManualProfile(String moduleId, List<String> conceptTags, String chapterSection,
                                List<String> prevKnowledge, List<String> nextExtension,
                                List<String> discussionStarters, List<String> caseLibrary,
                                List<String> theoryLibrary) {
    }
}
