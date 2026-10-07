/*
 * 源自 xuxueli/xxl-rpc（Apache License 2.0）。
 * 版权与许可声明见仓库根目录 NOTICE 第 3 节；本文件的修改同样按
 * Apache-2.0 与本仓库整体许可中较严格者对外提供。
 * Original: Copyright 2015-2020 XiuXueletian (xuxueli)
 */

package com.wugui.datax.rpc.old.remoting.net;//package com.xxl.rpc.remoting.net;
//
//import com.xxl.rpc.remoting.net.impl.netty.client.NettyClient;
//import com.xxl.rpc.remoting.net.impl.netty.server.NettyServer;
//import com.xxl.rpc.remoting.net.impl.netty_http.client.NettyHttpClient;
//import com.xxl.rpc.remoting.net.impl.netty_http.server.NettyHttpServer;
//
///**
// * remoting net
// *
// * @author xuxueli 2015-11-24 22:09:57
// */
//public enum NetEnum {
//
//
//	/**
//	 * netty tcp server
//	 */
//	NETTY(NettyServer.class, NettyClient.class),
//
//	/**
//	 * netty http server
//	 */
//	NETTY_HTTP(NettyHttpServer.class, NettyHttpClient.class);
//
//
//	public final Class<? extends Server> serverClass;
//	public final Class<? extends Client> clientClass;
//
//	NetEnum(Class<? extends Server> serverClass, Class<? extends Client> clientClass) {
//		this.serverClass = serverClass;
//		this.clientClass = clientClass;
//	}
//
//	public static NetEnum autoMatch(String name, NetEnum defaultEnum) {
//		for (NetEnum item : NetEnum.values()) {
//			if (item.name().equals(name)) {
//				return item;
//			}
//		}
//		return defaultEnum;
//	}
//
//}