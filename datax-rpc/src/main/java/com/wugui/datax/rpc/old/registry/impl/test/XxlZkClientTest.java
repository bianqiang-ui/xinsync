/*
 * 源自 xuxueli/xxl-rpc（Apache License 2.0）。
 * 版权与许可声明见仓库根目录 NOTICE 第 3 节；本文件的修改同样按
 * Apache-2.0 与本仓库整体许可中较严格者对外提供。
 * Original: Copyright 2015-2020 XiuXueletian (xuxueli)
 */

package com.wugui.datax.rpc.old.registry.impl.test;//package com.xxl.rpc.test;
//
//import com.xxl.rpc.util.XxlZkClient;
//
//import java.util.concurrent.TimeUnit;
//
//public class XxlZkClientTest {
//
//    public static void main(String[] args) throws InterruptedException {
//
//        XxlZkClient client = null;
//        try {
//            client = new XxlZkClient("127.0.0.1:2181", "/xxl-rpc/test", null, null);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//
//        for (int i = 0; i < 100; i++) {
//            System.out.println("------------- " + i);
//            try {
//                System.out.println(client.getClient());
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//            TimeUnit.SECONDS.sleep(5);
//        }
//
//    }
//
//}