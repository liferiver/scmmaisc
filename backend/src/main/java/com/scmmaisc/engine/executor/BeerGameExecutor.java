package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 啤酒游戏（牛鞭效应）执行器（T026，CH8-001）。
 * 4 级供应链（零售商→批发商→分销商→制造商），自动补货策略（基本库存策略：
 * order = 需求 + (目标库存 - 库存位置)），需求前 5 轮稳定、第 6 轮起跳升并叠加
 * 正态噪声（RandomSource 播种，R-05）；info_share 开启后上游直接见最终客户需求。
 * 参数 key（T024 约定）：levels / initial_demand / demand_jump / lead_time /
 * holding_cost / stockout_cost / info_share / demand_noise / total_rounds。
 */
@Component
public class BeerGameExecutor implements ScenarioExecutor {

    private static final int STABLE_ROUNDS = 5;

    /** 各级节点名称：链路始终以制造商为顶端（levels=5 时中间插入经销商）。 */
    private static List<String> nodeNames(int levels) {
        return switch (levels) {
            case 3 -> List.of("零售商", "批发商", "制造商");
            case 4 -> List.of("零售商", "批发商", "分销商", "制造商");
            default -> List.of("零售商", "批发商", "分销商", "经销商", "制造商");
        };
    }

    @Override
    public String engineKey() {
        return "beer-game";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "levels", 3, 5, errors);
        intParam(params, "initial_demand", 1, 20, errors);
        intParam(params, "demand_jump", 1, 100, errors);
        intParam(params, "lead_time", 1, 3, errors);
        doubleParam(params, "holding_cost", 0.5, 2, errors);
        doubleParam(params, "stockout_cost", 2, 10, errors);
        doubleParam(params, "demand_noise", 0, 2, errors);
        intParam(params, "total_rounds", 30, 50, errors);
        Object infoShare = params.get("info_share");
        if (infoShare == null) {
            errors.add("参数 info_share 缺失");
        } else if (!(infoShare instanceof Boolean)) {
            errors.add("参数 info_share 必须为布尔值");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return ((Number) params.getOrDefault("total_rounds", 50)).intValue();
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int levels = ((Number) params.get("levels")).intValue();
        List<String> names = nodeNames(levels);
        int initialDemand = ((Number) params.get("initial_demand")).intValue();
        int demandJump = ((Number) params.get("demand_jump")).intValue();
        int lead = ((Number) params.get("lead_time")).intValue();
        double holdingCost = ((Number) params.get("holding_cost")).doubleValue();
        double stockoutCost = ((Number) params.get("stockout_cost")).doubleValue();
        double noiseScale = ((Number) params.get("demand_noise")).doubleValue();
        boolean infoShare = (Boolean) params.get("info_share");
        int rounds = ((Number) params.get("total_rounds")).intValue();

        // 状态：库存 / 欠货（未履约的向下游承诺）/ 在途管线 transit[i][k] = 再过 k 轮到货
        int[] inv = new int[levels];
        int[] back = new int[levels];
        int[] orders = new int[levels];
        int[] prevOrders = new int[levels];
        int[] stockoutCount = new int[levels];
        int[][] transit = new int[levels][lead];

        // 稳态初始化：各级库存与在途 = 稳定期需求，避免启动瞬态
        for (int i = 0; i < levels; i++) {
            inv[i] = lead * initialDemand;
            prevOrders[i] = initialDemand;
            for (int k = 0; k < lead; k++) {
                transit[i][k] = initialDemand;
            }
        }

        // 序列收集（确定性顺序）
        List<Integer> customerSeries = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Integer>[] orderSeries = new List[levels];
        @SuppressWarnings("unchecked")
        List<Integer>[] invSeries = new List[levels];
        @SuppressWarnings("unchecked")
        List<Integer>[] stockSeries = new List[levels];
        for (int i = 0; i < levels; i++) {
            orderSeries[i] = new ArrayList<>();
            invSeries[i] = new ArrayList<>();
            stockSeries[i] = new ArrayList<>();
        }
        double totalCost = 0;

        for (int r = 1; r <= rounds; r++) {
            if (ctx.isCancelled()) {
                break;
            }
            // 客户需求：前 5 轮稳定，第 6 轮起跳升，叠加正态噪声
            double base = r <= STABLE_ROUNDS ? initialDemand : demandJump;
            long noise = Math.round(ctx.random().nextGaussian() * noiseScale);
            int cust = (int) Math.max(0, base + noise);
            customerSeries.add(cust);

            for (int i = 0; i < levels; i++) {
                // 各环节可见需求：零售商见客户需求；上游见下游上轮订单；信息共享则全员见客户需求
                int demandSeen = (i == 0) ? cust : prevOrders[i - 1];
                if (infoShare) {
                    demandSeen = cust;
                }
                // 到货（先履约后决策）
                inv[i] += transit[i][0];
                for (int k = 0; k < lead - 1; k++) {
                    transit[i][k] = transit[i][k + 1];
                }
                transit[i][lead - 1] = 0;
                // 向下游发货：先补欠货，再履新单；发出量进入下游在途管线
                int need = demandSeen + back[i];
                int ship = Math.min(inv[i], need);
                inv[i] -= ship;
                if (need - ship > 0) {
                    stockoutCount[i]++;
                }
                if (i > 0) {
                    transit[i - 1][lead - 1] += ship;
                }
                back[i] = need - ship;
                // 订货决策：基本库存策略（目标库存 = 提前期 × 需求）
                int inflight = 0;
                for (int k = 0; k < lead; k++) {
                    inflight += transit[i][k];
                }
                int target = lead * demandSeen;
                int order = Math.max(0, demandSeen + target - (inv[i] - back[i] + inflight));
                orders[i] = order;
                transit[i][lead - 1] += order;
            }
            System.arraycopy(orders, 0, prevOrders, 0, levels);

            // 本轮成本与序列记录
            for (int i = 0; i < levels; i++) {
                totalCost += holdingCost * Math.max(0, inv[i]) + stockoutCost * Math.max(0, back[i]);
                orderSeries[i].add(orders[i]);
                invSeries[i].add(inv[i]);
                stockSeries[i].add(stockoutCount[i]);
            }

            // 步骤事件（FR-009：逐轮可回放）
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("round", r);
            data.put("customer_demand", cust);
            data.put("orders", copyList(orders));
            data.put("inventories", copyList(inv));
            data.put("total_cost", Math.round(totalCost * 100.0) / 100.0);
            ctx.step(String.format("第 %d 轮：客户需求 %d 箱，订单 [%s]",
                    r, cust, joinOrders(orders, names)), data);
        }

        // 输出指标（FR-007，声明顺序固定）
        Map<String, Object> ordersSeries = buildSeries(rounds, orderSeries, levels, names);
        Map<String, Object> invSeriesOut = buildSeries(rounds, invSeries, levels, names);
        Map<String, Object> stockSeriesOut = buildSeries(rounds, stockSeries, levels, names);
        List<Map<String, Object>> bullwhip = bullwhipIndex(customerSeries, orderSeries, levels, names);
        ctx.output("orders_series", "各节点订单量", "series", ordersSeries, "箱");
        ctx.output("inventory_series", "各节点库存波动", "series", invSeriesOut, "箱");
        ctx.output("bullwhip_index", "牛鞭效应指数（订单方差/需求方差）", "compare", bullwhip, null);
        ctx.output("total_cost", "供应链总成本", "scalar", Math.round(totalCost * 100.0) / 100.0, "元");
        ctx.output("stockout_counts", "各节点缺货次数", "series", stockSeriesOut, "次");
    }

    // ---- 模型工具 ----

    private static Map<String, Object> buildSeries(int rounds, List<Integer>[] series, int levels, List<String> names) {
        List<Integer> x = new ArrayList<>();
        for (int r = 1; r <= rounds; r++) {
            x.add(r);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < levels; i++) {
            lines.add(Map.of("name", names.get(i), "data", series[i]));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", x);
        out.put("series", lines);
        return out;
    }

    /** 牛鞭效应指数：BE = 各节点订单方差 / 客户需求方差（总体方差）。 */
    private static List<Map<String, Object>> bullwhipIndex(List<Integer> customer, List<Integer>[] orders, int levels, List<String> names) {
        double demandVar = variance(customer);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < levels; i++) {
            double be = demandVar < 1e-12 ? 1.0 : variance(orders[i]) / demandVar;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", names.get(i));
            item.put("value", Math.round(be * 100.0) / 100.0);
            items.add(item);
        }
        return items;
    }

    private static double variance(List<Integer> values) {
        if (values.size() < 2) {
            return 0;
        }
        double mean = 0;
        for (int v : values) {
            mean += v;
        }
        mean /= values.size();
        double sum = 0;
        for (int v : values) {
            sum += (v - mean) * (v - mean);
        }
        return sum / values.size();
    }

    private static List<Integer> copyList(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int v : values) {
            list.add(v);
        }
        return list;
    }

    private static String joinOrders(int[] orders, List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(names.get(i)).append(' ').append(orders[i]);
        }
        return sb.toString();
    }

    private static Integer intParam(Map<String, Object> params, String key, int min, int max, List<String> errors) {
        Object raw = params.get(key);
        if (raw == null) {
            errors.add("参数 " + key + " 缺失");
            return null;
        }
        if (!(raw instanceof Number)) {
            errors.add("参数 " + key + " 必须为整数");
            return null;
        }
        int v = ((Number) raw).intValue();
        if (v < min || v > max) {
            errors.add(key + " 超出范围 [" + min + ", " + max + "]");
            return null;
        }
        return v;
    }

    private static Double doubleParam(Map<String, Object> params, String key, double min, double max, List<String> errors) {
        Object raw = params.get(key);
        if (raw == null) {
            errors.add("参数 " + key + " 缺失");
            return null;
        }
        if (!(raw instanceof Number)) {
            errors.add("参数 " + key + " 必须为数值");
            return null;
        }
        double v = ((Number) raw).doubleValue();
        if (v < min || v > max) {
            errors.add(key + " 超出范围 [" + min + ", " + max + "]");
            return null;
        }
        return v;
    }
}
