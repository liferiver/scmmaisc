package com.scmmaisc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.common.BizException;
import com.scmmaisc.common.ErrorCode;
import com.scmmaisc.common.ParamsGuard;
import com.scmmaisc.engine.OutputValue;
import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import com.scmmaisc.engine.SimResult;
import com.scmmaisc.engine.SimulationEngine;
import com.scmmaisc.engine.StepEvent;
import com.scmmaisc.engine.ExecutorRegistry;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.entity.SimulationRun;
import com.scmmaisc.mapper.ScenarioMapper;
import com.scmmaisc.mapper.SimulationRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 运行服务（T023，R-04/R-11）：创建运行 → 异步执行 SimulationEngine →
 * 步骤事件经 StepListener 逐条落库 simulation_log → 状态机
 * RUNNING → COMPLETED / CANCELLED / FAILED（终态不可变）。
 * 契约：C4 创建 / C5 状态轮询 / C6 结果 / C7 取消；clientId 归属校验（403）。
 * 安全加固（T041）：clientId 白名单格式、请求体必填校验、params 结构守卫、
 * 运行失败错误信息不泄露内部细节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunService {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_FAILED = "FAILED";

    private static final long DEFAULT_SEED = 42L;

    /** clientId 白名单：4-64 位字母/数字/下划线/连字符（UUID 与前端兜底格式均符合）。 */
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{4,64}");

    /** 运行失败时对外展示的通用文案：具体异常仅写入服务端日志（T041，不泄露内部细节）。 */
    private static final String RUN_FAILED_MESSAGE = "仿真运行失败，请调整参数后重试";

    private final ScenarioMapper scenarioMapper;
    private final SimulationRunMapper runMapper;
    private final SimLogService logService;
    private final ExecutorRegistry registry;
    private final ObjectMapper objectMapper;

    @Qualifier("runExecutor")
    private final ThreadPoolTaskExecutor runExecutor;

    /** 活跃运行上下文：DELETE 取消时向对应 SimContext 置取消标志（FR-015）。 */
    private final ConcurrentHashMap<Long, SimContext> activeRuns = new ConcurrentHashMap<>();

    /** C4：创建并启动运行。校验失败（400）发生在创建阶段，不进 RUNNING。 */
    public CreateRunResult create(ScenarioRunRequest request) {
        if (request.scenarioId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "scenarioId 必填");
        }
        requireClientIdFormat(request.clientId());
        if (request.seed() != null && request.seed() < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "seed 必须为非负整数");
        }
        Map<String, Object> params = new LinkedHashMap<>(
                request.params() == null ? Map.of() : request.params());
        List<String> shapeErrors = ParamsGuard.validate(params);
        if (!shapeErrors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "参数校验不通过：" + String.join("；", shapeErrors));
        }
        Scenario scenario = scenarioMapper.selectById(request.scenarioId());
        if (scenario == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + request.scenarioId());
        }
        ScenarioExecutor executor = registry.require(scenario.getEngineKey());
        List<String> errors = new SimulationEngine(executor).validate(params);
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "参数校验不通过：" + String.join("；", errors));
        }

        SimulationRun run = new SimulationRun();
        run.setScenarioId(scenario.getId());
        run.setClientId(request.clientId());
        run.setParams(toJson(params));
        run.setSeed(request.seed() == null ? DEFAULT_SEED : request.seed());
        run.setStatus(STATUS_RUNNING);
        run.setStepTotal(new SimulationEngine(executor).describeSteps(params));
        run.setStepCount(0);
        runMapper.insert(run);

        Long runId = run.getId();
        runExecutor.execute(() -> execute(runId));
        return new CreateRunResult(runId, STATUS_RUNNING);
    }

    /** C5：运行状态与进度（终态 progress=1）。 */
    public RunStatusVO status(Long runId, String clientId) {
        SimulationRun run = requireOwned(runId, clientId);
        double progress;
        if (isTerminal(run.getStatus())) {
            progress = 1.0;
        } else if (run.getStepTotal() != null && run.getStepTotal() > 0) {
            progress = (double) run.getStepCount() / run.getStepTotal();
        } else {
            progress = 0.0;
        }
        return new RunStatusVO(runId, run.getScenarioId(), run.getStatus(), run.getStepTotal(),
                run.getStepCount(), progress, run.getErrorMessage());
    }

    /** C6：运行结果（输出指标 + 全部步骤日志）；仅 COMPLETED 可读。 */
    public RunResultVO result(Long runId, String clientId) {
        SimulationRun run = requireOwned(runId, clientId);
        if (!STATUS_COMPLETED.equals(run.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "运行尚未完成: " + run.getStatus());
        }
        List<StepEvent> steps = logService.listByRun(runId);
        List<OutputValue> outputs = parseOutputs(run.getResult());
        return new RunResultVO(runId, run.getStatus(), parseParams(run), run.getSeed(),
                run.getDurationMs(), outputs, steps);
    }

    /** C7：取消运行（终态返回 409；clientId 不匹配 403）。 */
    public CancelResult cancel(Long runId, String clientId) {
        SimulationRun run = requireOwned(runId, clientId);
        if (!STATUS_RUNNING.equals(run.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "运行已处于终态: " + run.getStatus());
        }
        SimContext ctx = activeRuns.get(runId);
        if (ctx != null) {
            ctx.cancel();
        }
        return new CancelResult(runId, STATUS_CANCELLED);
    }

    // ---- 异步执行 ----

    private void execute(Long runId) {
        SimulationRun run = runMapper.selectById(runId);
        long start = System.currentTimeMillis();
        SimContext ctx = null;
        try {
            Scenario scenario = scenarioMapper.selectById(run.getScenarioId());
            ScenarioExecutor executor = registry.require(scenario.getEngineKey());
            Map<String, Object> params = parseParams(run);
            SimulationEngine engine = new SimulationEngine(executor);
            // 上下文由本服务持有：DELETE 取消时向同一实例置标志（FR-015）
            ctx = engine.context(params, run.getSeed());
            activeRuns.put(runId, ctx);

            SimResult result = engine.run(ctx, event -> {
                // R-04：过程即日志 —— 每步先落库，再更新进度
                logService.append(runId, event);
                runMapper.update(null, new LambdaUpdateWrapper<SimulationRun>()
                        .eq(SimulationRun::getId, runId)
                        .set(SimulationRun::getStepCount, event.stepNo()));
            });

            run.setStepCount(result.steps().size());
            run.setResult(toJson(result.outputs()));
            run.setStatus(result.cancelled() ? STATUS_CANCELLED : STATUS_COMPLETED);
        } catch (Exception e) {
            // 完整异常仅记录到服务端日志；对外仅返回通用文案（T041，不泄露内部细节）
            log.error("运行失败: runId={}", runId, e);
            run.setStatus(STATUS_FAILED);
            run.setErrorMessage(RUN_FAILED_MESSAGE);
        } finally {
            if (ctx != null) {
                activeRuns.remove(runId);
            }
            run.setDurationMs(System.currentTimeMillis() - start);
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
        }
    }

    // ---- 工具 ----

    private SimulationRun requireOwned(Long runId, String clientId) {
        requireClientIdFormat(clientId);
        SimulationRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行不存在: " + runId);
        }
        if (!run.getClientId().equals(clientId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "运行记录不属于该客户端");
        }
        return run;
    }

    /** clientId 白名单校验：不满足格式时直接 400（T041）。 */
    private static void requireClientIdFormat(String clientId) {
        if (clientId == null || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "clientId 格式不合法（4-64 位字母/数字/下划线/连字符）");
        }
    }

    private static boolean isTerminal(String status) {
        return STATUS_COMPLETED.equals(status) || STATUS_CANCELLED.equals(status) || STATUS_FAILED.equals(status);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("运行数据序列化失败", e);
        }
    }

    private Map<String, Object> parseParams(SimulationRun run) {
        try {
            return objectMapper.readValue(run.getParams(), new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("运行参数解析失败", e);
        }
    }

    private List<OutputValue> parseOutputs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<OutputValue>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("运行结果解析失败", e);
        }
    }

    // ---- 契约模型 ----

    /** C4 请求体。 */
    public record ScenarioRunRequest(Long scenarioId, String clientId, Map<String, Object> params, Long seed) {
    }

    /** C4 响应体：{ runId, status }。 */
    public record CreateRunResult(Long runId, String status) {
    }

    /** C5 响应体：状态与进度。 */
    public record RunStatusVO(Long runId, Long scenarioId, String status, Integer stepTotal,
                              Integer stepCount, Double progress, String errorMessage) {
    }

    /** C6 响应体：输出指标 + 全部步骤日志。 */
    public record RunResultVO(Long runId, String status, Map<String, Object> params, Long seed,
                              Long durationMs, List<OutputValue> outputs, List<StepEvent> steps) {
    }

    /** C7 响应体。 */
    public record CancelResult(Long runId, String status) {
    }
}
