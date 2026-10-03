package com.wugui.datatx.core.biz.model;

import com.wugui.datax.rpc.util.SensitiveLogMask;

import java.io.Serializable;
import java.util.Date;


/**
 * Created by xuxueli on 16/7/22.
 */
public class TriggerParam implements Serializable{
    private static final long serialVersionUID = 42L;

    private int jobId;

    private String executorHandler;
    private String executorParams;
    private String executorBlockStrategy;
    private int executorTimeout;

    private long logId;
    private long logDateTime;

    private String glueType;
    private String glueSource;
    private long glueUpdatetime;

    private int broadcastIndex;
    private int broadcastTotal;

    private String jobJson;
    private String processId;

    private String replaceParam;
    private String jvmParam;
    private Date startTime;
    private Date triggerTime;

    private String partitionInfo;

    private long startId;
    private long endId;

    private Integer incrementType;

    private String replaceParamType;


    public int getJobId() {
        return jobId;
    }

    public void setJobId(int jobId) {
        this.jobId = jobId;
    }

    public String getExecutorHandler() {
        return executorHandler;
    }

    public void setExecutorHandler(String executorHandler) {
        this.executorHandler = executorHandler;
    }

    public String getExecutorParams() {
        return executorParams;
    }

    public void setExecutorParams(String executorParams) {
        this.executorParams = executorParams;
    }

    public String getExecutorBlockStrategy() {
        return executorBlockStrategy;
    }

    public void setExecutorBlockStrategy(String executorBlockStrategy) {
        this.executorBlockStrategy = executorBlockStrategy;
    }

    public int getExecutorTimeout() {
        return executorTimeout;
    }

    public void setExecutorTimeout(int executorTimeout) {
        this.executorTimeout = executorTimeout;
    }

    public long getLogId() {
        return logId;
    }

    public void setLogId(long logId) {
        this.logId = logId;
    }

    public long getLogDateTime() {
        return logDateTime;
    }

    public void setLogDateTime(long logDateTime) {
        this.logDateTime = logDateTime;
    }

    public String getGlueType() {
        return glueType;
    }

    public void setGlueType(String glueType) {
        this.glueType = glueType;
    }

    public String getGlueSource() {
        return glueSource;
    }

    public void setGlueSource(String glueSource) {
        this.glueSource = glueSource;
    }

    public long getGlueUpdatetime() {
        return glueUpdatetime;
    }

    public void setGlueUpdatetime(long glueUpdatetime) {
        this.glueUpdatetime = glueUpdatetime;
    }

    public int getBroadcastIndex() {
        return broadcastIndex;
    }

    public void setBroadcastIndex(int broadcastIndex) {
        this.broadcastIndex = broadcastIndex;
    }

    public int getBroadcastTotal() {
        return broadcastTotal;
    }

    public void setBroadcastTotal(int broadcastTotal) {
        this.broadcastTotal = broadcastTotal;
    }

    public String getJobJson() {
        return jobJson;
    }

    public void setJobJson(String jobJson) {
        this.jobJson = jobJson;
    }

    public String getProcessId() {
        return processId;
    }

    public void setProcessId(String processId) {
        this.processId = processId;
    }

    public String getReplaceParam() {
        return replaceParam;
    }

    public void setReplaceParam(String replaceParam) {
        this.replaceParam = replaceParam;
    }

    public String getJvmParam() {
        return jvmParam;
    }

    public void setJvmParam(String jvmParam) {
        this.jvmParam = jvmParam;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getTriggerTime() {
        return triggerTime;
    }

    public void setTriggerTime(Date triggerTime) {
        this.triggerTime = triggerTime;
    }

    public String getPartitionInfo() {
        return partitionInfo;
    }

    public void setPartitionInfo(String partitionInfo) {
        this.partitionInfo = partitionInfo;
    }

    public long getStartId() {
        return startId;
    }

    public void setStartId(long startId) {
        this.startId = startId;
    }

    public long getEndId() {
        return endId;
    }

    public void setEndId(long endId) {
        this.endId = endId;
    }

    public Integer getIncrementType() {
        return incrementType;
    }

    public void setIncrementType(Integer incrementType) {
        this.incrementType = incrementType;
    }

    public String getReplaceParamType() {
        return replaceParamType;
    }

    public void setReplaceParamType(String replaceParamType) {
        this.replaceParamType = replaceParamType;
    }
    @Override
    public String toString() {
        return "TriggerParam{" +
                "jobId=" + jobId +
                ", executorHandler='" + executorHandler + '\'' +
                ", executorParams='" + executorParams + '\'' +
                ", executorBlockStrategy='" + executorBlockStrategy + '\'' +
                ", executorTimeout=" + executorTimeout +
                ", logId=" + logId +
                ", logDateTime=" + logDateTime +
                ", glueType='" + glueType + '\'' +
                //  这两个字段是「整段内容本身就是敏感物」的那一类：glueSource 是用户写的脚本，
                //  jobJson 是 JobTrigger 派发那一刻刚还原出来的**明文数据源账密**。原先它们被 toString
                //  原样带出，而 toString 的下游不止一条出口（RPC 超时异常消息、invoke error 日志、
                //  以及那些消息再拼进 job_log.trigger_msg 落库）。收口只能收在这儿，打印处逐个遮是追不上的。
                ", glueSource=" + SensitiveLogMask.describeBlob(glueSource) +
                ", glueUpdatetime=" + glueUpdatetime +
                ", broadcastIndex=" + broadcastIndex +
                ", broadcastTotal=" + broadcastTotal +
                ", jobJson=" + SensitiveLogMask.describeBlob(jobJson) +
                ", processId=" + processId +
                ", replaceParam=" + replaceParam +
                ", jvmParam=" + jvmParam +
                ", startTime=" + startTime +
                ", triggerTime=" + triggerTime +
                ", partitionInfo=" + partitionInfo +
                ", replaceParamType=" + replaceParamType +
                ", startId=" + startId +
                ", endId=" + endId +
                ", incrementType=" + incrementType +
                '}';
    }

}
