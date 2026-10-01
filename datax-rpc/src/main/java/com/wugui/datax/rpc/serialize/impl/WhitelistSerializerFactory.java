package com.wugui.datax.rpc.serialize.impl;

import com.caucho.hessian.io.Deserializer;
import com.caucho.hessian.io.HessianProtocolException;
import com.caucho.hessian.io.SerializerFactory;

/**
 * 白名单 SerializerFactory：RPC 反序列化只接受任务调度相关的类型，阻断 Hessian 反序列化 gadget 链。
 */
public class WhitelistSerializerFactory extends SerializerFactory {

    public static final WhitelistSerializerFactory INSTANCE = new WhitelistSerializerFactory();

    private static final String[] ALLOWED_PREFIXES = {
            "[",
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void",
            "com.wugui.",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
            "java.lang.String", "java.lang.Number", "java.lang.Enum", "java.lang.StackTraceElement",
            "java.lang.Object", "java.util.", "java.math.", "java.time.", "java.sql.",
    };

    private static final String[] DENIED_PREFIXES = {
            "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Thread",
            "java.lang.Class", "java.lang.Package", "java.lang.Module",
            "java.util.ServiceLoader", "java.util.logging.", "java.util.prefs.",
            "java.util.concurrent.Executors", "java.util.jar.", "java.util.zip.",
    };

    private WhitelistSerializerFactory() {
        super();
    }

    @Override
    public Deserializer getDeserializer(String type) throws HessianProtocolException {
        if (!isAllowed(type)) {
            throw new HessianProtocolException("deserialization of '" + type + "' is not allowed");
        }
        return super.getDeserializer(type);
    }

    private static boolean isAllowed(String type) {
        if (type == null || type.length() == 0) {
            return false;
        }
        for (String denied : DENIED_PREFIXES) {
            if (type.startsWith(denied)) {
                return false;
            }
        }
        for (String allowed : ALLOWED_PREFIXES) {
            if (type.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }

}
