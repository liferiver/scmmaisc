package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 中欧班列运营网络仿真执行器（T056，CH5-004）。
 * 模型：集结中心(6 城) → 国内铁路(标准轨) → 口岸换装(轨距变换+口岸容量) → 宽轨段 →
 * 欧洲枢纽分拨，全程分节点累计时效；经济性按 元/TEU 对比班列/海运/空运，
 * 盈亏装载率 = 单列固定成本 ÷ (41 TEU × 单箱边际贡献)；各集结中心竞争力打分排名。
 */
@Component
public class CrerExecutor implements ScenarioExecutor {

    private static final Set<String> ORIGINS =
            Set.of("chongqing", "chengdu", "xian", "zhengzhou", "wuhan", "yiwu");
    private static final Set<String> PORTS =
            Set.of("alashankou", "horgos", "manzhouli", "erlianhot");
    private static final Set<String> CATEGORIES =
            Set.of("electronics", "auto_parts", "apparel", "food", "ecommerce");

    /** 集结中心：到杜伊斯堡全程(km) / 国内铁路段(天) / 每周班列开行能力。 */
    private static final Map<String, double[]> ORIGIN_DATA = Map.of(
            "chongqing", new double[]{11179, 3.0, 5},
            "chengdu", new double[]{9965, 3.0, 4},
            "xian", new double[]{9850, 2.5, 4},
            "zhengzhou", new double[]{10200, 3.5, 3},
            "wuhan", new double[]{10800, 4.0, 2},
            "yiwu", new double[]{13000, 5.0, 2});
    /** 出境口岸：换装/通关基准耗时(天) / 宽轨段到欧洲枢纽距离(km)。 */
    private static final Map<String, double[]> PORT_DATA = Map.of(
            "alashankou", new double[]{0.5, 6500},
            "horgos", new double[]{0.6, 6100},
            "manzhouli", new double[]{1.0, 7200},
            "erlianhot", new double[]{1.5, 6900});
    private static final String[] ORIGIN_NAMES =
            {"重庆", "成都", "西安", "郑州", "武汉", "义乌"};
    private static final List<String> ORIGIN_ORDER =
            List.of("chongqing", "chengdu", "xian", "zhengzhou", "wuhan", "yiwu");

    @Override
    public String engineKey() {
        return "crer";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        String origin = enumParam(params, "origin_city", ORIGINS, errors);
        String port = enumParam(params, "exit_port", PORTS, errors);
        Integer freq = intParam(params, "train_frequency", 1, 7, errors);
        Double loading = doubleParam(params, "loading_rate", 0.5, 1.0, errors);
        String category = enumParam(params, "cargo_category", CATEGORIES, errors);
        Double euDays = doubleParam(params, "eu_distribution_days", 1, 5, errors);
        Double balance = doubleParam(params, "balance_ratio", 0.3, 0.8, errors);
        if (errors.isEmpty() && origin != null && port != null && freq != null && loading != null
                && category != null && euDays != null && balance != null) {
            // 约束 loading_ok：装载率低于约 60% 的临界盈亏点将亏损
            if (loading < 0.6) {
                errors.add("loading_ok 约束不满足：装载率需 ≥ 0.6（临界盈亏装载率约 60%）");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 全程时效（天）：0.5 集结 + 发运等待 + 国内段 + 口岸换装 + 宽轨段 + 欧洲分拨。 */
    private double transitDays(String origin, String port, int freq, double euDays) {
        double wait = 3.5 / freq; // 平均等待半个班列间隔
        double domestic = ORIGIN_DATA.get(origin)[1];
        double portDelay = PORT_DATA.get(port)[0] + 1.0 + Math.max(0, 3.5 / freq - 1.0) * 0.2;
        double wide = PORT_DATA.get(port)[1] / 850.0;
        return 0.5 + wait + domestic + portDelay + wide + euDays;
    }

    /** 班列单箱成本（元/TEU）：基础 + 里程单价，受品类与双向平衡率修正。 */
    private double railCostPerTeu(String origin, String category, double balance) {
        double km = ORIGIN_DATA.get(origin)[0];
        double cost = 18000 + km * 0.55;
        double categoryFactor = switch (category) {
            case "food" -> 1.15;    // 冷链附加
            case "apparel" -> 0.9;
            default -> 1.0;
        };
        double balanceFactor = 1 + (0.8 - balance) * 0.5; // 平衡率低 → 空箱返回成本高
        return cost * categoryFactor * balanceFactor;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String origin = String.valueOf(params.get("origin_city"));
        String port = String.valueOf(params.get("exit_port"));
        int freq = ((Number) params.get("train_frequency")).intValue();
        double loading = ((Number) params.get("loading_rate")).doubleValue();
        String category = String.valueOf(params.get("cargo_category"));
        double euDays = ((Number) params.get("eu_distribution_days")).doubleValue();
        double balance = ((Number) params.get("balance_ratio")).doubleValue();

        // 步骤 1：集结与发运等待
        double wait = 3.5 / freq;
        ctx.step(String.format("%s 集结中心，班列频次 %d 班/周 → 平均等待 %.1f 天",
                        ORIGIN_NAMES[ORIGIN_ORDER.indexOf(origin)], freq, wait),
                Map.of("origin", origin, "wait_days", round2(wait)));

        // 步骤 2-4：国内段 → 口岸换装 → 宽轨段（累计时效曲线）
        double domestic = ORIGIN_DATA.get(origin)[1];
        double portDelay = PORT_DATA.get(port)[0] + 1.0 + Math.max(0, 3.5 / freq - 1.0) * 0.2;
        double wide = PORT_DATA.get(port)[1] / 850.0;
        double total = 0.5 + wait + domestic + portDelay + wide + euDays;
        List<Double> cum = new ArrayList<>();
        cum.add(round2(0.5 + wait));
        cum.add(round2(0.5 + wait + domestic));
        cum.add(round2(0.5 + wait + domestic + portDelay));
        cum.add(round2(0.5 + wait + domestic + portDelay + wide));
        cum.add(round2(total));
        ctx.step(String.format("国内铁路 %.1f 天 → %s 口岸换装 %.1f 天（轨距变换+通关）→ 宽轨段 %.1f 天",
                        domestic, port, portDelay, wide),
                Map.of("domestic_days", round2(domestic), "port_days", round2(portDelay),
                        "wide_days", round2(wide)));

        // 步骤 5：经济性对比与盈亏装载率
        double railCost = railCostPerTeu(origin, category, balance);
        double seaCost = 10500 + 20000 * 0.4;
        double airCost = 220000 + 10000 * 1.0;
        double capacity = 41; // 班列标准箱位
        double revenue = 24000; // 西向单箱运价（元/TEU）
        double variable = 3000; // 单箱变动成本
        double breakeven = 540000 / (capacity * (revenue - variable)) * 100;
        boolean profitable = loading >= breakeven / 100;
        List<Map<String, Object>> costCompare = List.of(
                Map.of("name", "中欧班列", "value", round2(railCost)),
                Map.of("name", "海运", "value", round2(seaCost)),
                Map.of("name", "空运", "value", round2(airCost)));
        ctx.step(String.format("单箱成本：班列 %.0f 元 vs 海运 %.0f 元 vs 空运 %.0f 元；盈亏装载率 %.1f%%（当前 %.0f%% %s）",
                        railCost, seaCost, airCost, breakeven, loading * 100,
                        profitable ? "盈利" : "亏损"),
                Map.of("breakeven_loading", round2(breakeven), "profitable", profitable));

        // 各集结中心竞争力排名：时效与成本归一化加权
        Map<String, Double> scoreMap = new LinkedHashMap<>();
        double maxT = 0, maxC = 0;
        for (String o : ORIGINS) {
            maxT = Math.max(maxT, transitDays(o, port, freq, euDays));
            maxC = Math.max(maxC, railCostPerTeu(o, category, balance));
        }
        for (String o : ORIGINS) {
            double normT = (transitDays(o, port, freq, euDays) - 0.5) / (maxT - 0.5);
            double normC = (railCostPerTeu(o, category, balance) - 10500) / (maxC - 10500);
            scoreMap.put(o, 100 * (1 - (normT * 0.5 + normC * 0.5)));
        }
        List<Map<String, Object>> ranking = new ArrayList<>();
        ORIGINS.stream().sorted((a, b) -> Double.compare(scoreMap.get(b), scoreMap.get(a)))
                .forEach(o -> ranking.add(Map.of("name", ORIGIN_NAMES[ORIGIN_ORDER.indexOf(o)],
                        "value", round2(scoreMap.get(o)))));
        ctx.step("集结中心竞争力排名（时效 50% + 成本 50%）：" + ranking.get(0).get("name") + " 最优",
                Map.of("ranking", ranking));

        // 输出指标（FR-007）
        ctx.output("total_transit_time", "全程运输时间", "series",
                series(List.of(1, 2, 3, 4, 5), "累计时效(天)", cum), "天");
        ctx.output("cost_compare", "成本对比(班列vs海运vs空运)", "compare", costCompare, "元/TEU");
        ctx.output("breakeven_loading", "盈亏装载率", "scalar", round2(breakeven), "%");
        ctx.output("center_ranking", "各集结中心竞争力排名", "compare", ranking, null);
    }
}
