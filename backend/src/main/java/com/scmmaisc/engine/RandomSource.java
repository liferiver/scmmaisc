package com.scmmaisc.engine;

import java.util.Random;

/**
 * 可复现随机源（R-05）：包装 {@link java.util.Random}，以显式 seed 构造。
 * 相同 seed + 相同调用序列 → 完全一致的结果（FR-008 / SC-005）。
 * 执行器禁止直接使用其它未播种随机源。
 */
public class RandomSource {

    private final Random random;

    public RandomSource(long seed) {
        this.random = new Random(seed);
    }

    /** [0,1) 均匀分布。 */
    public double nextDouble() {
        return random.nextDouble();
    }

    /** 标准正态分布 N(0,1)。 */
    public double nextGaussian() {
        return random.nextGaussian();
    }

    /** [0,bound) 均匀整数。 */
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    /** 均匀长整数。 */
    public long nextLong() {
        return random.nextLong();
    }

    /** 等概率布尔。 */
    public boolean nextBoolean() {
        return random.nextBoolean();
    }
}
