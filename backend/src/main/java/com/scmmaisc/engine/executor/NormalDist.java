package com.scmmaisc.engine.executor;

/**
 * 标准正态分布工具（T053）：zα 分位数表（教材常用近似值）、概率密度 φ、累积分布 Φ
 * 与损失函数（用于 (s,Q) 策略缺货量估算与库存模型对比）。
 */
final class NormalDist {

    private NormalDist() {
    }

    /** 常用服务水平 → 标准正态分位数（教材近似值，z(0.95)=1.65 与算例一致）。 */
    private static final double[][] Z_TABLE = {
            {0.90, 1.28},
            {0.95, 1.65},
            {0.975, 1.96},
            {0.99, 2.33},
            {0.995, 2.58},
            {0.999, 3.09}};

    /** zα：目标服务水平 α 对应的标准正态分位数（表内线性插值，边界取表值）。 */
    static double zNormal(double alpha) {
        if (alpha <= Z_TABLE[0][0]) {
            return Z_TABLE[0][1];
        }
        for (int i = 1; i < Z_TABLE.length; i++) {
            if (alpha <= Z_TABLE[i][0]) {
                double a0 = Z_TABLE[i - 1][0];
                double z0 = Z_TABLE[i - 1][1];
                double a1 = Z_TABLE[i][0];
                double z1 = Z_TABLE[i][1];
                return z0 + (alpha - a0) / (a1 - a0) * (z1 - z0);
            }
        }
        return Z_TABLE[Z_TABLE.length - 1][1];
    }

    /** 标准正态概率密度 φ(z)。 */
    static double phi(double z) {
        return Math.exp(-z * z / 2) / Math.sqrt(2 * Math.PI);
    }

    /** 标准正态累积分布 Φ(z)（Abramowitz-Stegun 7.1.26 近似）。 */
    static double cdf(double z) {
        if (z < 0) {
            return 1 - cdf(-z);
        }
        double t = 1 / (1 + 0.3275911 * z);
        double erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-z * z);
        return 0.5 * (1 + erf);
    }

    /** 标准正态损失函数 L(z) = φ(z) − z(1−Φ(z))：用于期望缺货量 E[max(X−s,0)]。 */
    static double loss(double z) {
        return phi(z) - z * (1 - cdf(z));
    }
}
