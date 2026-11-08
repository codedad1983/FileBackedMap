package com.codedad.fbmap.bench;

import java.util.Random;

/**
 * 确定性样本生成器：键/值长度在 [100, 1000] 个字符之间随机生成。
 * 同一个下标永远得到同一个键，因此不必把键留在内存里，跨进程也能重现同一组样本。
 */
final class SampleGen {

    static final int MIN_LEN = 100;
    static final int MAX_LEN = 1000;

    private static final char[] POOL =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.".toCharArray();

    private SampleGen() {
    }

    static String keyFor(int i) {
        long seed = 0x9E3779B97F4A7C15L * (i + 1L) ^ (0xBF58476D1CE4E5B9L * (i + 7L));
        Random r = new Random(seed);
        int total = MIN_LEN + r.nextInt(MAX_LEN - MIN_LEN + 1);
        String prefix = "k" + i + "#";
        return prefix + randomText(r, Math.max(0, total - prefix.length()));
    }

    static String valueFor(long seed, int i) {
        Random r = new Random(seed + i * 0x9E3779B97F4A7C15L);
        int len = MIN_LEN + r.nextInt(MAX_LEN - MIN_LEN + 1);
        return randomText(r, len);
    }

    private static String randomText(Random r, int len) {
        char[] c = new char[len];
        int p = POOL.length;
        for (int i = 0; i < len; i++) {
            c[i] = POOL[r.nextInt(p)];
        }
        return new String(c);
    }
}
