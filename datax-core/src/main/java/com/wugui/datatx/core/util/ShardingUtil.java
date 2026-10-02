package com.wugui.datatx.core.util;

/**
 * sharding vo
 *
 * @author xuxueli 2017-07-25 21:26:38
 */
public class ShardingUtil {

    private static InheritableThreadLocal<ShardingVO> contextHolder = new InheritableThreadLocal<>();

    public static class ShardingVO {

        private int index;  // sharding index
        private int total;  // sharding total

        public ShardingVO(int index, int total) {
            this.index = index;
            this.total = total;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }
    }

    public static void setShardingVo(ShardingVO shardingVo) {
        contextHolder.set(shardingVo);
    }

    public static ShardingVO getShardingVo() {
        return contextHolder.get();
    }

    /**
     * 清除本次触发写入的分片上下文。
     * 子线程在创建时已经拷贝了一份 InheritableThreadLocal，父线程这里 remove 不会影响正在跑的超时线程。
     */
    public static void removeShardingVo() {
        contextHolder.remove();
    }

}
