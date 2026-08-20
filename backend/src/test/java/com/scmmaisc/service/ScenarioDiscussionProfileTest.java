package com.scmmaisc.service;

import com.scmmaisc.entity.Scenario;
import com.scmmaisc.mapper.ChapterMapper;
import com.scmmaisc.mapper.ScenarioDiscussionProfileMapper;
import com.scmmaisc.mapper.ScenarioMapper;
import com.scmmaisc.service.discussion.ScenarioDiscussionProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景讨论配置测试（T043，US5）：84 场景 100% 覆盖（SC-001）、自动生成规则
 * （concept 按顿号/逗号拆分 → concept_tags；deps → prev_knowledge；章节 → chapter_section；
 * 全局知识链路映射 → next_extension，research.md §6 三条链路）、人工覆盖文件优先级
 * （source=MANUAL 整体替换 AUTO，测试资源覆盖文件为准）、幂等 upsert。
 * H2 自包含：@BeforeEach 清空后重新触发 ScenarioDataLoader 装载 84 场景，
 * 不依赖其他测试类遗留数据（讨论表清理会级联清掉 scenario/profile）。
 */
@SpringBootTest
class ScenarioDiscussionProfileTest {

    @Autowired
    private ScenarioDiscussionProfileService profileService;

    @Autowired
    private ScenarioDataLoader scenarioDataLoader;

    @Autowired
    private ScenarioMapper scenarioMapper;

    @Autowired
    private ChapterMapper chapterMapper;

    @Autowired
    private ScenarioDiscussionProfileMapper profileMapper;

    @BeforeEach
    void setUp() throws Exception {
        profileMapper.delete(null);
        scenarioMapper.delete(null);
        chapterMapper.delete(null);
        scenarioDataLoader.run(null); // 重新装载 84 场景（幂等）
        profileService.loadAll(); // 重新装载全部配置（AUTO + MANUAL 覆盖）
    }

    @Test
    @DisplayName("84 场景 100% 覆盖：每个 scenario 恰好一份配置（SC-001）")
    void allScenariosCovered() {
        long scenarios = scenarioMapper.selectCount(null);
        assertEquals(84, scenarios);
        assertEquals(scenarios, profileService.count());
        for (Scenario s : scenarioMapper.selectList(null)) {
            assertNotNull(profileService.findByScenarioId(s.getId()),
                    "场景 " + s.getModuleId() + " 缺少讨论配置");
        }
    }

    @Test
    @DisplayName("自动生成规则：concept 拆分→concept_tags；deps→prev_knowledge；章节→chapter_section；链路→next_extension")
    void autoGenerationRules() {
        ScenarioDiscussionProfileService.ProfileVO vo = profileService.getByModuleId("CH2-006");

        assertEquals("AUTO", vo.source());
        // concept 按顿号拆分（"(s,Q)连续盘点策略、安全库存 SS=zα·σ·√L、服务水平 CSL/fill rate、正态/泊松需求" → 4 个标签）
        assertEquals(4, vo.conceptTags().size());
        assertTrue(vo.conceptTags().contains("安全库存 SS=zα·σ·√L"));
        // 章节定位取章节名（数据驱动，零虚构）
        assertEquals("第二章 物流系统控制", vo.chapterSection());
        // deps=["CH2-003"] → 前序知识含场景名
        assertTrue(vo.prevKnowledge().contains("EOQ经济订货批量"));
        // 库存链路：CH2-003 → CH2-006 → CH7-002 → CH8-001 → CH9-002 → 后续延伸含推拉策略/牛鞭效应
        assertTrue(vo.nextExtension().contains("推动/拉动/推拉结合策略仿真"));
        assertTrue(vo.nextExtension().contains("啤酒游戏——牛鞭效应"));
        // 未配置项：切入点用通用模板，案例/理论库为空（零虚构）
        assertTrue(vo.discussionStarters().size() >= 3);
        assertTrue(vo.caseLibrary().isEmpty());
        assertTrue(vo.theoryLibrary().isEmpty());
    }

    @Test
    @DisplayName("人工覆盖文件优先级：MANUAL 整体替换 AUTO（测试资源 CH2-003.json）")
    void manualOverrideWins() {
        ScenarioDiscussionProfileService.ProfileVO vo = profileService.getByModuleId("CH2-003");

        assertEquals("MANUAL", vo.source());
        // 整体替换：concept_tags 仅含文件值（AUTO 拆分应为 5 个）
        assertEquals(1, vo.conceptTags().size());
        assertEquals("测试覆盖：经济订货批量", vo.conceptTags().get(0));
        assertTrue(vo.discussionStarters().contains("测试人工覆盖：比较 Q* 与整批订货的成本差异"));
        assertEquals(1, vo.caseLibrary().size());
        assertEquals(1, vo.theoryLibrary().size());
    }

    @Test
    @DisplayName("幂等 upsert：重复装载不产生重复行，既有行更新不新增")
    void idempotentUpsert() {
        long before = profileService.count();
        profileService.loadAll();
        assertEquals(before, profileService.count());
        // module_id 唯一：重复装载后仍各一行
        for (String moduleId : new String[]{"CH2-003", "CH2-006", "CH4-004", "CH7-002"}) {
            Long rows = profileMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.scmmaisc.entity.ScenarioDiscussionProfile>()
                            .eq(com.scmmaisc.entity.ScenarioDiscussionProfile::getModuleId, moduleId));
            assertEquals(1L, rows, "module_id=" + moduleId + " 应恰好一行");
        }
    }

    @Test
    @DisplayName("D8 查询：未知 moduleId → null（控制器映射 404）")
    void unknownModuleReturnsNull() {
        assertNull(profileService.getByModuleId("CH9-999"));
    }
}
