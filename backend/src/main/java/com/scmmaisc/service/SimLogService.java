package com.scmmaisc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.engine.StepEvent;
import com.scmmaisc.entity.SimulationLog;
import com.scmmaisc.mapper.SimulationLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 模拟执行日志服务（T023，R-04 "过程保存为日志"）：
 * 步骤事件逐条写入 simulation_log，并支撑 C6 分步回放（FR-009）。
 */
@Service
@RequiredArgsConstructor
public class SimLogService {

    private final SimulationLogMapper logMapper;
    private final ObjectMapper objectMapper;

    /** 追加一条步骤日志（引擎 StepListener 回调，异步线程调用）。 */
    public void append(Long runId, StepEvent event) {
        SimulationLog log = new SimulationLog();
        log.setRunId(runId);
        log.setStepNo(event.stepNo());
        log.setEventType(event.eventType());
        log.setMessage(event.message());
        log.setData(toJson(event.data()));
        logMapper.insert(log);
    }

    /** 按 runId 返回全部步骤（stepNo 升序，C6 回放数据源）。 */
    public List<StepEvent> listByRun(Long runId) {
        return logMapper.selectList(
                        new LambdaQueryWrapper<SimulationLog>()
                                .eq(SimulationLog::getRunId, runId)
                                .orderByAsc(SimulationLog::getStepNo))
                .stream()
                .map(l -> new StepEvent(l.getStepNo(), l.getEventType(), l.getMessage(), parseData(l.getData())))
                .toList();
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (IOException e) {
            throw new IllegalStateException("步骤数据序列化失败", e);
        }
    }

    private Map<String, Object> parseData(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("步骤数据解析失败: " + json, e);
        }
    }
}
