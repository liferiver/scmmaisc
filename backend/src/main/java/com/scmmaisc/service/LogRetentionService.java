package com.scmmaisc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scmmaisc.entity.SimulationLog;
import com.scmmaisc.entity.SimulationRun;
import com.scmmaisc.mapper.SimulationLogMapper;
import com.scmmaisc.mapper.SimulationRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 日志保留策略（T039，宪法 IV）：每日凌晨清理 30 天前的历史运行记录，
 * 并级联删除其模拟日志。simulation_log 外键已设 ON DELETE CASCADE，
 * 此处先显式删除日志再删运行，保证无外键环境（如部分测试库）下行为一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogRetentionService {

    /** 默认保留天数：30 天。 */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    private final SimulationRunMapper runMapper;
    private final SimulationLogMapper logMapper;

    /** 每日 03:00 清理过期运行记录（可被测试直接调用核心逻辑）。 */
    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledCleanup() {
        int removed = cleanupExpired(DEFAULT_RETENTION_DAYS);
        if (removed > 0) {
            log.info("日志保留清理完成：删除 {} 条 30 天前运行记录及其日志", removed);
        }
    }

    /**
     * 删除 retentionDays 天前已结束的运行（按 finished_at 判定），
     * 以及长时间未结束的僵尸运行（finished_at 为空且 started_at 超期），
     * 返回删除的运行记录条数。
     */
    @Transactional
    public int cleanupExpired(int retentionDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<SimulationRun> expired = runMapper.selectList(new LambdaQueryWrapper<SimulationRun>()
                .and(w -> w.isNotNull(SimulationRun::getFinishedAt)
                        .lt(SimulationRun::getFinishedAt, cutoff)
                        .or()
                        .isNull(SimulationRun::getFinishedAt)
                        .lt(SimulationRun::getStartedAt, cutoff)));
        if (expired.isEmpty()) {
            return 0;
        }
        List<Long> ids = expired.stream().map(SimulationRun::getId).toList();
        logMapper.delete(new LambdaQueryWrapper<SimulationLog>().in(SimulationLog::getRunId, ids));
        runMapper.deleteBatchIds(ids);
        return ids.size();
    }
}
