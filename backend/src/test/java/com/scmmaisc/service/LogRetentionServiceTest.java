package com.scmmaisc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scmmaisc.entity.Chapter;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.entity.SimulationLog;
import com.scmmaisc.entity.SimulationRun;
import com.scmmaisc.mapper.ChapterMapper;
import com.scmmaisc.mapper.ScenarioMapper;
import com.scmmaisc.mapper.SimulationLogMapper;
import com.scmmaisc.mapper.SimulationRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 日志保留策略测试（T039）：30 天前的历史运行（含其日志）被清理、近期记录保留、
 * 长时间未结束的僵尸运行同样被清理、无过期数据时不误删。
 */
@SpringBootTest
class LogRetentionServiceTest {

    @Autowired
    private LogRetentionService retentionService;

    @Autowired
    private ChapterMapper chapterMapper;

    @Autowired
    private ScenarioMapper scenarioMapper;

    @Autowired
    private SimulationRunMapper runMapper;

    @Autowired
    private SimulationLogMapper logMapper;

    private long scenarioId;

    @BeforeEach
    void setUp() {
        logMapper.delete(null);
        runMapper.delete(null);
        scenarioMapper.delete(null);
        chapterMapper.delete(null);

        Chapter chapter = new Chapter();
        chapter.setCode("CH2");
        chapter.setName("物流系统控制");
        chapter.setSortNo(2);
        chapterMapper.insert(chapter);

        Scenario scenario = new Scenario();
        scenario.setChapterId(chapter.getId());
        scenario.setModuleId("CH2-003");
        scenario.setName("EOQ经济订货批量");
        scenario.setEngineKey("eoq");
        scenario.setDifficulty("intro");
        scenario.setClassHours(2);
        scenario.setIsRolePlay(false);
        scenario.setConcept("概念");
        scenario.setDescription("流程");
        scenario.setDeps("[]");
        scenario.setParams("[]");
        scenario.setOutputs("[]");
        scenario.setConstraints("[]");
        scenarioMapper.insert(scenario);
        scenarioId = scenario.getId();
    }

    private long insertRun(LocalDateTime finishedAt, String status) {
        SimulationRun run = new SimulationRun();
        run.setScenarioId(scenarioId);
        run.setClientId("test-client");
        run.setParams("{}");
        run.setSeed(42L);
        run.setStatus(status);
        run.setFinishedAt(finishedAt);
        runMapper.insert(run);
        return run.getId();
    }

    private void insertLog(long runId) {
        SimulationLog log = new SimulationLog();
        log.setRunId(runId);
        log.setStepNo(1);
        log.setEventType("STEP");
        log.setMessage("步骤1");
        logMapper.insert(log);
    }

    @Test
    @DisplayName("清理 30 天前运行并级联删除其日志，近期记录保留")
    void cleanupExpiredRemovesOldRunsAndLogs() {
        long oldRun = insertRun(LocalDateTime.now().minusDays(40), "COMPLETED");
        insertLog(oldRun);
        long recentRun = insertRun(LocalDateTime.now().minusDays(5), "COMPLETED");
        insertLog(recentRun);

        int removed = retentionService.cleanupExpired(LogRetentionService.DEFAULT_RETENTION_DAYS);

        assertEquals(1, removed);
        assertNull(runMapper.selectById(oldRun));
        assertEquals(0, logMapper.selectCount(
                new LambdaQueryWrapper<SimulationLog>().eq(SimulationLog::getRunId, oldRun)));
        assertNotNull(runMapper.selectById(recentRun));
        assertEquals(1, logMapper.selectCount(
                new LambdaQueryWrapper<SimulationLog>().eq(SimulationLog::getRunId, recentRun)));
    }

    @Test
    @DisplayName("长时间未结束的僵尸运行（finished_at 为空）同样被清理")
    void cleanupExpiredRemovesZombieRunning() {
        SimulationRun zombie = new SimulationRun();
        zombie.setScenarioId(scenarioId);
        zombie.setClientId("test-client");
        zombie.setParams("{}");
        zombie.setSeed(1L);
        zombie.setStatus("RUNNING");
        zombie.setStartedAt(LocalDateTime.now().minusDays(40));
        runMapper.insert(zombie);
        insertLog(zombie.getId());

        int removed = retentionService.cleanupExpired(30);

        assertEquals(1, removed);
        assertEquals(0, runMapper.selectCount(
                new LambdaQueryWrapper<SimulationRun>().eq(SimulationRun::getId, zombie.getId())));
        assertEquals(0, logMapper.selectCount(
                new LambdaQueryWrapper<SimulationLog>().eq(SimulationLog::getRunId, zombie.getId())));
    }

    @Test
    @DisplayName("无过期记录时不删除任何数据")
    void cleanupWithNothingExpired() {
        long recentRun = insertRun(LocalDateTime.now().minusDays(1), "COMPLETED");
        insertLog(recentRun);

        int removed = retentionService.cleanupExpired(30);

        assertEquals(0, removed);
        assertEquals(1, runMapper.selectCount(null));
        assertEquals(1, logMapper.selectCount(null));
    }
}
