package com.wugui.datax.admin.core.trigger;

import com.wugui.datatx.core.biz.ExecutorBiz;
import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datatx.core.biz.model.TriggerParam;
import com.wugui.datatx.core.enums.ExecutorBlockStrategyEnum;
import com.wugui.datatx.core.enums.IncrementTypeEnum;
import com.wugui.datatx.core.glue.GlueTypeEnum;
import com.wugui.datax.admin.core.conf.JobAdminConfig;
import com.wugui.datax.admin.core.route.ExecutorRouteStrategyEnum;
import com.wugui.datax.admin.core.scheduler.JobScheduler;
import com.wugui.datax.admin.core.util.I18nUtil;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.entity.JobGroup;
import com.wugui.datax.admin.entity.JobInfo;
import com.wugui.datax.admin.entity.JobLog;
import com.wugui.datax.admin.tool.datax.DsSecretPlaceholder;
import com.wugui.datax.admin.tool.query.BaseQueryTool;
import com.wugui.datax.admin.tool.query.QueryToolFactory;
import com.wugui.datax.admin.util.JSONUtils;
import com.wugui.datax.rpc.util.IpUtil;
import com.wugui.datax.rpc.util.SensitiveLogMask;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Date;

/**
 * xxl-job trigger
 * Created by xuxueli on 17/7/13.
 */
public class JobTrigger {
    private static Logger logger = LoggerFactory.getLogger(JobTrigger.class);

    /**
     * trigger job
     *
     * @param jobId
     * @param triggerType
     * @param failRetryCount        >=0: use this param
     *                              <0: use param from job info config
     * @param executorShardingParam
     * @param executorParam         null: use job param
     *                              not null: cover job param
     */
    public static void trigger(int jobId, TriggerTypeEnum triggerType, int failRetryCount, String executorShardingParam, String executorParam) {
        JobInfo jobInfo = JobAdminConfig.getAdminConfig().getJobInfoMapper().loadById(jobId);
        if (jobInfo == null) {
            logger.warn(">>>>>>>>>>>> trigger fail, jobId invalid，jobId={}", jobId);
            return;
        }
        if (GlueTypeEnum.BEAN.getDesc().equals(jobInfo.getGlueType())) {
            //解密账密
            String json;
            try {
                json = JSONUtils.changeJson(jobInfo.getJobJson(), JSONUtils.decrypt);
                // 新任务的 job_json 里只有 @@DATAX_DS_*@@ 引用，明文在这一行才出现，
                // 且只出现在即将发往执行器的那份 TriggerParam 里（落库/API/模板三面都不再有凭据）。
                json = DsSecretPlaceholder.resolveForDispatch(json);
            } catch (Exception e) {
                // job_json 为空或结构不完整时这里会抛（JSONUtils:59 NPE）。异常原先直接逃进触发线程池，
                // 日志页一条记录都没有，用户只看到"任务失败了但没有运行日志"（社区 #389 的描述）
                String reason = "job_json 无法解析，或其中的数据源账密引用还原不了（数据源已删除 / 口令已清空 / 换过 DATAX_AES_KEY），任务未下发：" + e;
                triggerFailLog(jobInfo, triggerType, reason);
                return;
            }
            jobInfo.setJobJson(json);
        }
        if (StringUtils.isNotBlank(executorParam)) {
            jobInfo.setExecutorParam(executorParam);
        }
        int finalFailRetryCount = failRetryCount >= 0 ? failRetryCount : jobInfo.getExecutorFailRetryCount();
        JobGroup group = JobAdminConfig.getAdminConfig().getJobGroupMapper().load(jobInfo.getJobGroup());

        // sharding param
        int[] shardingParam = null;
        if (executorShardingParam != null) {
            String[] shardingArr = executorShardingParam.split("/");
            if (shardingArr.length == 2 && isNumeric(shardingArr[0]) && isNumeric(shardingArr[1])) {
                shardingParam = new int[2];
                shardingParam[0] = Integer.valueOf(shardingArr[0]);
                shardingParam[1] = Integer.valueOf(shardingArr[1]);
            }
        }
        if (ExecutorRouteStrategyEnum.SHARDING_BROADCAST == ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null)
                && group.getRegistryList() != null && !group.getRegistryList().isEmpty()
                && shardingParam == null) {
            for (int i = 0; i < group.getRegistryList().size(); i++) {
                processTrigger(group, jobInfo, finalFailRetryCount, triggerType, i, group.getRegistryList().size());
            }
        } else {
            if (shardingParam == null) {
                shardingParam = new int[]{0, 1};
            }
            processTrigger(group, jobInfo, finalFailRetryCount, triggerType, shardingParam[0], shardingParam[1]);
        }

    }

    private static boolean isNumeric(String str) {
        try {
            int result = Integer.valueOf(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 派发之前就失败时补一条调度日志：让失败原因出现在日志页面，而不是只留在 admin 控制台。
     */
    private static void triggerFailLog(JobInfo jobInfo, TriggerTypeEnum triggerType, String reason) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        calendar.set(Calendar.MILLISECOND, 0);

        JobLog jobLog = new JobLog();
        jobLog.setJobGroup(jobInfo.getJobGroup());
        jobLog.setJobId(jobInfo.getId());
        jobLog.setJobDesc(jobInfo.getJobDesc());
        jobLog.setTriggerTime(calendar.getTime());
        jobLog.setExecutorHandler(jobInfo.getExecutorHandler());
        jobLog.setTriggerCode(ReturnT.FAIL_CODE);
        jobLog.setTriggerMsg(I18nUtil.getString("jobconf_trigger_type") + "：" + triggerType.getTitle()
                + "<br>" + reason);
        JobAdminConfig.getAdminConfig().getJobLogMapper().save(jobLog);
        JobAdminConfig.getAdminConfig().getJobLogMapper().updateTriggerInfo(jobLog);

        logger.error(">>>>>>>>>>> datax-web, trigger fail before dispatch, jobId = {}, logId = {}, {}",
                jobInfo.getId(), jobLog.getId(), reason);
    }

    /**
     * @param group               job group, registry list may be empty
     * @param jobInfo
     * @param finalFailRetryCount
     * @param triggerType
     * @param index               sharding index
     * @param total               sharding index
     */
    private static void processTrigger(JobGroup group, JobInfo jobInfo, int finalFailRetryCount, TriggerTypeEnum triggerType, int index, int total) {

        TriggerParam triggerParam = new TriggerParam();

        // param
        ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(jobInfo.getExecutorBlockStrategy(), ExecutorBlockStrategyEnum.SERIAL_EXECUTION);  // block strategy
        ExecutorRouteStrategyEnum executorRouteStrategyEnum = ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null);    // route strategy
        String shardingParam = (ExecutorRouteStrategyEnum.SHARDING_BROADCAST == executorRouteStrategyEnum) ? String.valueOf(index).concat("/").concat(String.valueOf(total)) : null;

        // 1、save log-id
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        calendar.set(Calendar.MILLISECOND, 0);
        Date triggerTime = calendar.getTime();
        JobLog jobLog = new JobLog();
        jobLog.setJobGroup(jobInfo.getJobGroup());
        jobLog.setJobId(jobInfo.getId());
        jobLog.setTriggerTime(triggerTime);
        jobLog.setJobDesc(jobInfo.getJobDesc());

        JobAdminConfig.getAdminConfig().getJobLogMapper().save(jobLog);
        logger.debug(">>>>>>>>>>> datax-web trigger start, jobId:{}", jobLog.getId());

        // 2、init trigger-param
        triggerParam.setJobId(jobInfo.getId());
        triggerParam.setExecutorHandler(jobInfo.getExecutorHandler());
        triggerParam.setExecutorParams(jobInfo.getExecutorParam());
        triggerParam.setExecutorBlockStrategy(jobInfo.getExecutorBlockStrategy());
        triggerParam.setExecutorTimeout(jobInfo.getExecutorTimeout());
        triggerParam.setLogId(jobLog.getId());
        triggerParam.setLogDateTime(jobLog.getTriggerTime().getTime());
        triggerParam.setGlueType(jobInfo.getGlueType());
        triggerParam.setGlueSource(jobInfo.getGlueSource());
        triggerParam.setGlueUpdatetime(jobInfo.getGlueUpdatetime().getTime());
        triggerParam.setBroadcastIndex(index);
        triggerParam.setBroadcastTotal(total);
        triggerParam.setJobJson(jobInfo.getJobJson());

        //increment parameter
        Integer incrementType = jobInfo.getIncrementType();
        if (incrementType != null) {
            triggerParam.setIncrementType(incrementType);
            if (IncrementTypeEnum.ID.getCode() == incrementType) {
                long maxId;
                try {
                    maxId = getMaxId(jobInfo);
                } catch (Exception e) {
                    // 上界拿不到就绝不下发：原先 getMaxIdVal 会把异常吞成 0，endId=0 让执行器
                    // "报成功但零字节"，用户完全无从察觉（社区 #672）。这里必须是失败记录。
                    String reason = "增量主键上界获取失败，任务未下发：" + e;
                    logger.error(">>>>>>>>>>> datax-web, getMaxId fail, jobId = " + jobInfo.getId(), e);
                    jobLog.setTriggerCode(ReturnT.FAIL_CODE);
                    jobLog.setTriggerMsg(reason);
                    JobAdminConfig.getAdminConfig().getJobLogMapper().updateTriggerInfo(jobLog);
                    return;
                }
                jobLog.setMaxId(maxId);
                triggerParam.setEndId(maxId);
                triggerParam.setStartId(jobInfo.getIncStartId());
            } else if (IncrementTypeEnum.TIME.getCode() == incrementType) {
                triggerParam.setStartTime(jobInfo.getIncStartTime());
                triggerParam.setTriggerTime(triggerTime);
                triggerParam.setReplaceParamType(jobInfo.getReplaceParamType());
            } else if (IncrementTypeEnum.PARTITION.getCode() == incrementType) {
                triggerParam.setPartitionInfo(jobInfo.getPartitionInfo());
            }
            triggerParam.setReplaceParam(jobInfo.getReplaceParam());
        }
        //jvm parameter
        triggerParam.setJvmParam(jobInfo.getJvmParam());

        // 3、init address
        String address = null;
        ReturnT<String> routeAddressResult = null;
        if (group.getRegistryList() != null && !group.getRegistryList().isEmpty()) {
            if (ExecutorRouteStrategyEnum.SHARDING_BROADCAST == executorRouteStrategyEnum) {
                if (index < group.getRegistryList().size()) {
                    address = group.getRegistryList().get(index);
                } else {
                    address = group.getRegistryList().get(0);
                }
            } else {
                routeAddressResult = executorRouteStrategyEnum.getRouter().route(triggerParam, group.getRegistryList());
                if (routeAddressResult.getCode() == ReturnT.SUCCESS_CODE) {
                    address = routeAddressResult.getContent();
                }
            }
        } else {
            routeAddressResult = new ReturnT<String>(ReturnT.FAIL_CODE, I18nUtil.getString("jobconf_trigger_address_empty"));
        }

        // 4、trigger remote executor
        ReturnT<String> triggerResult = null;
        if (address != null) {
            triggerResult = runExecutor(triggerParam, address);
        } else {
            triggerResult = new ReturnT<String>(ReturnT.FAIL_CODE, null);
        }

        // 5、collection trigger info
        StringBuffer triggerMsgSb = new StringBuffer();
        triggerMsgSb.append(I18nUtil.getString("jobconf_trigger_type")).append("：").append(triggerType.getTitle());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobconf_trigger_admin_adress")).append("：").append(IpUtil.getIp());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobconf_trigger_exe_regtype")).append("：")
                .append((group.getAddressType() == 0) ? I18nUtil.getString("jobgroup_field_addressType_0") : I18nUtil.getString("jobgroup_field_addressType_1"));
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobconf_trigger_exe_regaddress")).append("：").append(group.getRegistryList());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_executorRouteStrategy")).append("：").append(executorRouteStrategyEnum.getTitle());
        if (shardingParam != null) {
            triggerMsgSb.append("(" + shardingParam + ")");
        }
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_executorBlockStrategy")).append("：").append(blockStrategy.getTitle());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_timeout")).append("：").append(jobInfo.getExecutorTimeout());
        triggerMsgSb.append("<br>").append(I18nUtil.getString("jobinfo_field_executorFailRetryCount")).append("：").append(finalFailRetryCount);

        triggerMsgSb.append("<br><br><span style=\"color:#00c0ef;\" > >>>>>>>>>>>" + I18nUtil.getString("jobconf_trigger_run") + "<<<<<<<<<<< </span><br>")
                .append((routeAddressResult != null && routeAddressResult.getMsg() != null) ? routeAddressResult.getMsg() + "<br><br>" : "").append(triggerResult.getMsg() != null ? triggerResult.getMsg() : "");

        // 6、save log trigger-info
        jobLog.setExecutorAddress(address);
        jobLog.setExecutorHandler(jobInfo.getExecutorHandler());
        jobLog.setExecutorParam(jobInfo.getExecutorParam());
        jobLog.setExecutorShardingParam(shardingParam);
        jobLog.setExecutorFailRetryCount(finalFailRetryCount);
        jobLog.setTriggerCode(triggerResult.getCode());
        jobLog.setTriggerMsg(triggerMsgSb.toString());
        JobAdminConfig.getAdminConfig().getJobLogMapper().updateTriggerInfo(jobLog);

        logger.debug(">>>>>>>>>>> datax-web trigger end, jobId:{}", jobLog.getId());
    }

    private static long getMaxId(JobInfo jobInfo) {
        JobDatasource datasource = JobAdminConfig.getAdminConfig().getJobDatasourceMapper().selectById(jobInfo.getDatasourceId());
        BaseQueryTool qTool = QueryToolFactory.getByDbType(datasource);
        return qTool.getMaxIdVal(jobInfo.getReaderTable(), jobInfo.getPrimaryKey());
    }

    /**
     * run executor
     *
     * @param triggerParam
     * @param address
     * @return
     */
    public static ReturnT<String> runExecutor(TriggerParam triggerParam, String address) {
        ReturnT<String> runResult = null;
        try {
            ExecutorBiz executorBiz = JobScheduler.getExecutorBiz(address);
            runResult = executorBiz.run(triggerParam);
        } catch (Exception e) {
            logger.error(">>>>>>>>>>> datax-web trigger error, please check if the executor[{}] is running.", address, e);
            // 异常堆栈可能包含完整的 TriggerParam（含解密后的 jobJson、glueSource、accessToken），
            // 这些内容会被写进 job_log.trigger_msg 持久化到库里，日志面越权之后就是明文口令泄漏。
            // 只保留异常类名和消息，不保留完整堆栈。
            String safeMsg = e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + sanitizeTriggerMsg(e.getMessage()) : "");
            runResult = new ReturnT<String>(ReturnT.FAIL_CODE, safeMsg);
        }

        StringBuffer runResultSB = new StringBuffer(I18nUtil.getString("jobconf_trigger_run") + "：");
        runResultSB.append("<br>address：").append(address);
        runResultSB.append("<br>code：").append(runResult.getCode());
        runResultSB.append("<br>msg：").append(runResult.getMsg());

        runResult.setMsg(runResultSB.toString());
        return runResult;
    }

    /**
     * 从 trigger_msg 里擦除可能包含的口令。
     *
     * 这是**第二层**，不是主防线：主防线是 {@code TriggerParam.toString()} / {@code XxlRpcRequest.toString()}
     * 根本不产出凭据（都走 {@link SensitiveLogMask}）。这一层留给两种存量形态：
     * 老执行器（升级前的 jar）回传的异常文本，以及别的模块自己拼出来的消息串。
     *
     * 原先这里自己抄了一份正则，只匹配未转义的 {@code "password":"..."} —— 而异常消息里 jobJson 常常是被
     * 当作字符串嵌进去的，引号带反斜杠（{@code \"password\":\"...\"}），那条规则一条都命中不了；
     * URL 形态那一条还漏了 {@code username}。现在判定与遮蔽都从同一份实现走，两种形态一起判。
     */
    private static String sanitizeTriggerMsg(String msg) {
        return SensitiveLogMask.maskSecretValues(msg);
    }

}
