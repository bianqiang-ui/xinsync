/*
 * 源自 xuxueli/xxl-rpc（Apache License 2.0）。
 * 版权与许可声明见仓库根目录 NOTICE 第 3 节；本文件的修改同样按
 * Apache-2.0 与本仓库整体许可中较严格者对外提供。
 * Original: Copyright 2015-2020 XiuXueletian (xuxueli)
 */

package com.wugui.datax.rpc.old.remoting.net.impl.jetty.server;//package com.xxl.rpc.remoting.net.impl.jetty.server;
//
//import com.xxl.rpc.remoting.net.Server;
//import com.xxl.rpc.remoting.provider.XxlRpcProviderFactory;
//import org.eclipse.jetty.server.Connector;
//import org.eclipse.jetty.server.Handler;
//import org.eclipse.jetty.server.ServerConnector;
//import org.eclipse.jetty.server.handler.HandlerCollection;
//import org.eclipse.jetty.util.thread.QueuedThreadPool;
//
///**
// * jetty server
// *
// * 		<!-- jetty (javax.servlet-api) -->
// * 		<dependency>
// * 			<groupId>org.eclipse.jetty</groupId>
// * 			<artifactId>jetty-server</artifactId>
// * 			<version>${jetty-server.version}</version>
// * 			<scope>provided</scope>
// * 		</dependency>
// * 		<dependency>
// * 			<groupId>org.eclipse.jetty</groupId>
// * 			<artifactId>jetty-client</artifactId>
// * 			<version>${jetty-server.version}</version>
// * 			<scope>provided</scope>
// * 		</dependency>
// *
// * @author xuxueli 2015-11-19 22:29:03
// */
//public class JettyServer extends Server {
//
//
//	private Thread thread;
//
//	@Override
//	public void start(final XxlRpcProviderFactory xxlRpcProviderFactory) throws Exception {
//
//		thread = new Thread(new Runnable() {
//
//			@Override
//			public void run() {
//
//				// The Server
//				org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server(
//						new QueuedThreadPool(xxlRpcProviderFactory.getMaxPoolSize(), xxlRpcProviderFactory.getCorePoolSize()));
//				// TODO, thread config, change to async servlet
//
//				// HTTP connector
//				ServerConnector connector = new ServerConnector(server);
//				/*if (ip!=null && ip.trim().length()>0) {	// TODO, support set registry ip、and bind ip
//					//connector.setHost(ip);	// The network interface this connector binds to as an IP address or a hostname.  If null or 0.0.0.0, then bind to all interfaces.
//				}*/
//				connector.setPort(xxlRpcProviderFactory.getPort());
//				server.setConnectors(new Connector[]{connector});
//
//				// Set a handler
//				HandlerCollection handlerc =new HandlerCollection();
//				handlerc.setHandlers(new Handler[]{new JettyServerHandler(xxlRpcProviderFactory)});
//				server.setHandler(handlerc);
//
//				try {
//					server.start();
//
//					logger.info(">>>>>>>>>>> xxl-rpc remoting server start success, nettype = {}, port = {}", JettyServer.class.getName(), xxlRpcProviderFactory.getPort());
//					onStarted();
//
//					server.join();
//				} catch (Exception e) {
//                    if (e instanceof InterruptedException) {
//                        logger.info(">>>>>>>>>>> xxl-rpc remoting server stop.");
//                    } else {
//                        logger.error(">>>>>>>>>>> xxl-rpc remoting server error.", e);
//                    }
//				} finally {
//					if (server!=null && server.isRunning()) {
//						try {
//							server.stop();
//							server.destroy();
//						} catch (Exception e) {
//							logger.error(e.getMessage(), e);
//						}
//					}
//				}
//			}
//		});
//		thread.setDaemon(true);	// daemon, service jvm, user thread leave >>> daemon leave >>> jvm leave
//		thread.start();
//	}
//
//
//	@Override
//	public void stop() throws Exception {
//
//		// destroy server thread
//		if (thread!=null && thread.isAlive()) {
//			thread.interrupt();
//		}
//
//		// on stop
//		onStoped();
//		logger.info(">>>>>>>>>>> xxl-rpc remoting server destroy success.");
//	}
//}
