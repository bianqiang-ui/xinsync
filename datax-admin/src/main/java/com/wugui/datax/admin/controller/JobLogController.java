package com.wugui.datax.admin.controller;

import com.wugui.datatx.core.biz.ExecutorBiz;
import com.wugui.datatx.core.biz.model.LogResult;
import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datatx.core.util.DateUtil;
import com.wugui.datax.admin.core.kill.KillJob;
import com.wugui.datax.admin.core.scheduler.JobScheduler;
import com.wugui.datax.admin.core.util.I18nUtil;
import com.wugui.datax.admin.entity.JobInfo;
import com.wugui.datax.admin.entity.JobLog;
import com.wugui.datax.admin.mapper.JobInfoMapper;
import com.wugui.datax.admin.mapper.JobLogMapper;
import com.wugui.datax.admin.security.AccessControl;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by jingwk on 2019/11/17
 */
@RestController
@RequestMapping("/api/log")
@Api(tags = "任务运行日志接口")
public class JobLogController extends BaseController {
    private static Logger logger = LoggerFactory.getLogger(JobLogController.class);

    @Resource
    public JobInfoMapper jobInfoMapper;
    @Resource
    public JobLogMapper jobLogMapper;

    @GetMapping("/pageList")
    @ApiOperation("运行日志列表")
    public ReturnT<Map<String, Object>> pageList(
            @RequestParam(required = false, defaultValue = "0") int current,
            @RequestParam(required = false, defaultValue = "10") int size,
            int jobGroup, int jobId, int logStatus, String filterTime) {

        // valid permission
        //JobInfoController.validPermission(request, jobGroup);	// 仅管理员支持查询全部；普通用户仅支持查询有权限的 jobGroup

        // parse param
        Date triggerTimeStart = null;
        Date triggerTimeEnd = null;
        if (filterTime != null && filterTime.trim().length() > 0) {
            String[] temp = filterTime.split(" - ");
            if (temp.length == 2) {
                triggerTimeStart = DateUtil.parseDateTime(temp[0]);
                triggerTimeEnd = DateUtil.parseDateTime(temp[1]);
            }
        }

        // page query
        List<JobLog> data = jobLogMapper.pageList((current - 1) * size, size, jobGroup, jobId, triggerTimeStart, triggerTimeEnd, logStatus);
        int cnt = jobLogMapper.pageListCount((current - 1) * size, size, jobGroup, jobId, triggerTimeStart, triggerTimeEnd, logStatus);

        // package result
        Map<String, Object> maps = new HashMap<>();
        maps.put("recordsTotal", cnt);        // 总记录数
        maps.put("recordsFiltered", cnt);    // 过滤后的总记录数
        maps.put("data", data);                    // 分页列表
        return new ReturnT<>(maps);
    }

    @RequestMapping(value = "/logDetailCat", method = RequestMethod.GET)
    @ApiOperation("运行日志详情")
    public ReturnT<LogResult> logDetailCat(String executorAddress, long triggerTime, long logId, int fromLineNum) {
        // executorAddress 必须由库里的执行记录说了算：原先它直接来自查询参数，
        // 这个接口就成了"让 admin 去访问任意地址"的探测器，而且 accessToken 会随请求头发到对方主机。
        JobLog jobLog = jobLogMapper.load(logId);
        if (jobLog == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, "执行记录不存在，logId = " + logId);
        }
        if (jobLog.getExecutorAddress() == null || !jobLog.getExecutorAddress().equals(executorAddress)) {
            logger.warn(">>>>>>>>>>> datax-web, logDetailCat rejected, logId={} 的登记地址与请求地址不一致", logId);
            return new ReturnT<>(ReturnT.FAIL_CODE, "执行器地址与该执行记录不匹配");
        }
        try {
            ExecutorBiz executorBiz = JobScheduler.getExecutorBiz(executorAddress);
            ReturnT<LogResult> logResult = executorBiz.log(triggerTime, logId, fromLineNum);

            // is end
            if (logResult.getContent() != null && fromLineNum > logResult.getContent().getToLineNum()) {
                if (jobLog.getHandleCode() > 0) {
                    logResult.getContent().setEnd(true);
                }
            }

            return logResult;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return new ReturnT<>(ReturnT.FAIL_CODE, e.getMessage());
        }
    }

    @RequestMapping(value = "/logKill", method = RequestMethod.POST)
    @ApiOperation("kill任务")
    public ReturnT<String> logKill(HttpServletRequest request, int id) {
        // base check
        // 原先是 load(id) 之后立刻 log.getJobId()：传一个不存在的 logId 就是 NPE，
        // 对外表现为 500 而不是"这条执行记录不存在"。
        JobLog log = jobLogMapper.load(id);
        if (log == null) {
            return new ReturnT<>(500, "执行记录不存在，logId = " + id);
        }
        JobInfo jobInfo = jobInfoMapper.loadById(log.getJobId());
        if (jobInfo == null) {
            return new ReturnT<>(500, I18nUtil.getString("jobinfo_glue_jobid_invalid"));
        }
        // 终止别人正在跑的任务同样是越权面：归属以库里那条任务为准
        String deny = AccessControl.denyUnlessAdminOrOwner(jobInfo.getUserId(), getCurrentUserId(request));
        if (deny != null) {
            return new ReturnT<>(500, deny);
        }
        if (ReturnT.SUCCESS_CODE != log.getTriggerCode()) {
            return new ReturnT<>(500, I18nUtil.getString("joblog_kill_log_limit"));
        }

        // request of kill
        ReturnT<String> runResult;
        try {
            ExecutorBiz executorBiz = JobScheduler.getExecutorBiz(log.getExecutorAddress());
            runResult = executorBiz.kill(jobInfo.getId());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            runResult = new ReturnT<>(500, e.getMessage());
        }

        if (ReturnT.SUCCESS_CODE == runResult.getCode()) {
            log.setHandleCode(ReturnT.FAIL_CODE);
            log.setHandleMsg(I18nUtil.getString("joblog_kill_log_byman") + ":" + (runResult.getMsg() != null ? runResult.getMsg() : ""));
            log.setHandleTime(new Date());
            jobLogMapper.updateHandleInfo(log);
            return new ReturnT<>(runResult.getMsg());
        } else {
            return new ReturnT<>(500, runResult.getMsg());
        }
    }

    @PostMapping("/clearLog")
    @ApiOperation("清理日志")
    public ReturnT<String> clearLog(int jobGroup, int jobId, int type) {
        // 清日志是**不可回滚的批量删除**，type=9 是直接清全库；这种面必须管理员专属，
        // 普通用户误触（或脚本乱调）就把别人的运行痕迹全没了。
        ReturnT<String> deny = AccessControl.requireAdmin();
        if (deny != null) {
            return deny;
        }

        Date clearBeforeTime = null;
        int clearBeforeNum = 0;
        if (type == 1) {
            clearBeforeTime = DateUtil.addMonths(new Date(), -1);    // 清理一个月之前日志数据
        } else if (type == 2) {
            clearBeforeTime = DateUtil.addMonths(new Date(), -3);    // 清理三个月之前日志数据
        } else if (type == 3) {
            clearBeforeTime = DateUtil.addMonths(new Date(), -6);    // 清理六个月之前日志数据
        } else if (type == 4) {
            clearBeforeTime = DateUtil.addYears(new Date(), -1);    // 清理一年之前日志数据
        } else if (type == 5) {
            clearBeforeNum = 1000;        // 清理一千条以前日志数据
        } else if (type == 6) {
            clearBeforeNum = 10000;        // 清理一万条以前日志数据
        } else if (type == 7) {
            clearBeforeNum = 30000;        // 清理三万条以前日志数据
        } else if (type == 8) {
            clearBeforeNum = 100000;    // 清理十万条以前日志数据
        } else if (type == 9) {
            clearBeforeNum = 0;            // 清理所有日志数据
        } else {
            return new ReturnT<>(ReturnT.FAIL_CODE, I18nUtil.getString("joblog_clean_type_invalid"));
        }

        List<Long> logIds;
        do {
            logIds = jobLogMapper.findClearLogIds(jobGroup, jobId, clearBeforeTime, clearBeforeNum, 1000);
            if (logIds != null && logIds.size() > 0) {
                jobLogMapper.clearLog(logIds);
            }
        } while (logIds != null && logIds.size() > 0);

        return ReturnT.SUCCESS;
    }

    @ApiOperation("停止该job作业")
    @PostMapping("/killJob")
    public ReturnT<String> killJob(@RequestBody JobLog log) {
        // 只认库里那条执行记录。原先 executorAddress / processId 全部取自请求体，
        // 任何登录用户都能借 admin 往任意地址下发 kill，并让执行器杀掉任意 PID（accessToken 也一并外送）。
        JobLog stored = jobLogMapper.load(log.getId());
        if (stored == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, "执行记录不存在，logId = " + log.getId());
        }
        if (stored.getExecutorAddress() == null || stored.getExecutorAddress().trim().length() == 0
                || stored.getProcessId() == null || stored.getProcessId().trim().length() == 0) {
            return new ReturnT<>(ReturnT.FAIL_CODE, "该执行记录没有登记执行器地址或进程号，无法终止");
        }
        return KillJob.trigger(stored.getId(), stored.getTriggerTime(),
                stored.getExecutorAddress(), stored.getProcessId());
    }
}
