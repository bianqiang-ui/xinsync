/*
 * 源自 xuxueli/xxl-rpc（Apache License 2.0）。
 * 版权与许可声明见仓库根目录 NOTICE 第 3 节；本文件的修改同样按
 * Apache-2.0 与本仓库整体许可中较严格者对外提供。
 * Original: Copyright 2015-2020 XiuXueletian (xuxueli)
 */

package com.wugui.datax.rpc.old.remoting.net.impl.mina.codec;//package com.xxl.rpc.remoting.net.impl.mina.codec;
//
//import org.apache.mina.core.buffer.IoBuffer;
//import org.apache.mina.core.session.IoSession;
//import org.apache.mina.filter.codec.ProtocolEncoder;
//import org.apache.mina.filter.codec.ProtocolEncoderOutput;
//
//import com.xxl.rpc.serialize.Serializer;
//
//public class MinaEncoder implements ProtocolEncoder {
//
//	private Class<?> genericClass;
//    private Serializer serializer;
//
//    public MinaEncoder(Class<?> genericClass, final Serializer serializer) {
//        this.genericClass = genericClass;
//        this.serializer = serializer;
//    }
//
//    @Override
//	public void encode(IoSession session, Object message, ProtocolEncoderOutput out) throws Exception {
//    	if (genericClass.isInstance(message)) {
//            byte[] datas = serializer.serialize(message);
//
//            IoBuffer buffer = IoBuffer.allocate(256);
//    		buffer.setAutoExpand(true);
//    		buffer.setAutoShrink(true);
//
//    		buffer.putInt(datas.length);
//    		buffer.put(datas);
//
//    		buffer.flip();
//    		session.write(buffer);
//        }
//	}
//
//	@Override
//	public void dispose(IoSession session) throws Exception {
//
//	}
//
//}
