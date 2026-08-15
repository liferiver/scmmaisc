package com.scmmaisc.engine;

/**
 * 输出指标值（对齐 contracts/api.md C6 与 data-model.md outputs 结构）。
 *
 * @param key   指标 key（与场景定义的 outputs[].key 对应）
 * @param label 指标中文名
 * @param type  scalar / series / compare / dist / topo / heatmap / gauge
 * @param value 指标值（scalar 为数值；series 为 {x, series[{name,data}]}；
 *              compare 为 [{name,value}]；dist 为直方图数组等，均由执行器按确定性顺序产出）
 * @param unit  单位，可为 null
 */
public record OutputValue(String key, String label, String type, Object value, String unit) {
}
