package com.wugui.datax.rpc.remoting.net.params;

import com.wugui.datax.rpc.util.SensitiveLogMask;

import java.io.Serializable;
import java.util.Arrays;

/**
 * request
 *
 * @author xuxueli 2015-10-29 19:39:12
 */
public class XxlRpcRequest implements Serializable {
	private static final long serialVersionUID = 42L;
	
	private String requestId;
	private long createMillisTime;
	private String accessToken;

    private String className;
    private String methodName;
    private Class<?>[] parameterTypes;
    private Object[] parameters;

	private String version;


	public String getRequestId() {
		return requestId;
	}

	public void setRequestId(String requestId) {
		this.requestId = requestId;
	}

	public long getCreateMillisTime() {
		return createMillisTime;
	}

	public void setCreateMillisTime(long createMillisTime) {
		this.createMillisTime = createMillisTime;
	}

	public String getAccessToken() {
		return accessToken;
	}

	public void setAccessToken(String accessToken) {
		this.accessToken = accessToken;
	}

	public String getClassName() {
		return className;
	}

	public void setClassName(String className) {
		this.className = className;
	}

	public String getMethodName() {
		return methodName;
	}

	public void setMethodName(String methodName) {
		this.methodName = methodName;
	}

	public Class<?>[] getParameterTypes() {
		return parameterTypes;
	}

	public void setParameterTypes(Class<?>[] parameterTypes) {
		this.parameterTypes = parameterTypes;
	}

	public Object[] getParameters() {
		return parameters;
	}

	public void setParameters(Object[] parameters) {
		this.parameters = parameters;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	@Override
	public String toString() {
		return "XxlRpcRequest{" +
				"requestId='" + requestId + '\'' +
				", createMillisTime=" + createMillisTime +
				", accessToken='" + SensitiveLogMask.describeSecret(accessToken) + '\'' +
				", className='" + className + '\'' +
				", methodName='" + methodName + '\'' +
				", parameterTypes=" + Arrays.toString(parameterTypes) +
				//  parameters 里跑的是 ExecutorBiz 的入参：run() 带 TriggerParam（jobJson 已在它自己那层遮掉），
				//  log() 带执行器回传的 handleMsg —— 那是 DataX 子进程的报错原文，里头可能自带
				//  "Access denied for user ..." 这类凭据片段，所以这一层再扫一遍值。
				", parameters=" + SensitiveLogMask.maskSecretValues(Arrays.toString(parameters)) +
				", version='" + version + '\'' +
				'}';
	}

}
