package com.scmmaisc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scmmaisc.entity.Chapter;
import com.scmmaisc.entity.DiscussionConclusion;
import com.scmmaisc.entity.DiscussionQuestion;
import com.scmmaisc.entity.DiscussionSession;
import com.scmmaisc.entity.DiscussionUtterance;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.entity.SimulationLog;
import com.scmmaisc.entity.SimulationRun;
import com.scmmaisc.mapper.ChapterMapper;
import com.scmmaisc.mapper.DiscussionConclusionMapper;
import com.scmmaisc.mapper.DiscussionQuestionMapper;
import com.scmmaisc.mapper.DiscussionSessionMapper;
import com.scmmaisc.mapper.DiscussionUtteranceMapper;
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
 * 长时间未结束的僵尸运行同样被清理、无过期数据时不误删；
 * T049：过期运行的讨论数据（会话/发言/插话/结论）显式级联清理，近期讨论保留。
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

    @Autowired
    private DiscussionSessionMapper sessionMapper;

    @Autowired
    private DiscussionUtteranceMapper utteranceMapper;

    @Autowired
    private DiscussionQuestionMapper questionMapper;

    @Autowired
    private DiscussionConclusionMapper conclusionMapper;

    private long scenarioId;

    @BeforeEach
    void setUp() {
        utteranceMapper.delete(null);
        conclusionMapper.delete(null);
        questionMapper.delete(null);
        sessionMapper.delete(null);
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

    /** 插入一条完整讨论记录（会话 + 发言 + 插话 + 结论），返回会话 ID。 */
    private long insertDiscussion(long runId) {
        DiscussionSession session = new DiscussionSession();
        session.setRunId(runId);
        session.setScenarioId(scenarioId);
        session.setClientId("test-client");
        session.setStatus("COMPLETED");
        session.setRoundNo(6);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);

        DiscussionUtterance utterance = new DiscussionUtterance();
        utterance.setSessionId(session.getId());
        utterance.setRoundNo(1);
        utterance.setAgentRole("JING");
        utterance.setContent("确定性发言");
        utteranceMapper.insert(utterance);

        DiscussionQuestion question = new DiscussionQuestion();
        question.setSessionId(session.getId());
        question.setRoundNo(1);
        question.setContent("测试插话");
        question.setResponded(true);
        question.setCreatedAt(LocalDateTime.now());
        questionMapper.insert(question);

        DiscussionConclusion conclusion = new DiscussionConclusion();
        conclusion.setSessionId(session.getId());
        conclusion.setTheoryJson("{}");
        conclusion.setPracticeJson("{}");
        conclusion.setFrontierJson("{}");
        conclusionMapper.insert(conclusion);
        return session.getId();
    }

    private long countUtterance(long sessionId) {
        return utteranceMapper.selectCount(
                new LambdaQueryWrapper<DiscussionUtterance>().eq(DiscussionUtterance::getSessionId, sessionId));
    }

    private long countQuestion(long sessionId) {
        return questionMapper.selectCount(
                new LambdaQueryWrapper<DiscussionQuestion>().eq(DiscussionQuestion::getSessionId, sessionId));
    }

    private long countConclusion(long sessionId) {
        return conclusionMapper.selectCount(
                new LambdaQueryWrapper<DiscussionConclusion>().eq(DiscussionConclusion::getSessionId, sessionId));
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
    @DisplayName("T049: 过期运行清理时讨论四表显式级联删除，近期讨论保留")
    void cleanupExpiredRemovesDiscussionData() {
        long oldRun = insertRun(LocalDateTime.now().minusDays(40), "COMPLETED");
        insertLog(oldRun);
        long oldSession = insertDiscussion(oldRun);
        long recentRun = insertRun(LocalDateTime.now().minusDays(5), "COMPLETED");
        insertLog(recentRun);
        long recentSession = insertDiscussion(recentRun);

        int removed = retentionService.cleanupExpired(LogRetentionService.DEFAULT_RETENTION_DAYS);

        assertEquals(1, removed, "仅清理过期运行");
        assertNull(runMapper.selectById(oldRun));
        assertNull(sessionMapper.selectById(oldSession), "过期会话已删");
        assertEquals(0, countUtterance(oldSession), "过期发言已删");
        assertEquals(0, countQuestion(oldSession), "过期插话已删");
        assertEquals(0, countConclusion(oldSession), "过期结论已删");
        assertNotNull(runMapper.selectById(recentRun));
        assertNotNull(sessionMapper.selectById(recentSession), "近期会话保留");
        assertEquals(1, countUtterance(recentSession));
        assertEquals(1, countQuestion(recentSession));
        assertEquals(1, countConclusion(recentSession));
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
