package com.wugui.datax.admin.controller;


import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datatx.core.util.DateUtil;
import com.wugui.datax.admin.core.cron.CronExpression;
import com.wugui.datax.admin.core.thread.JobTriggerPoolHelper;
import com.wugui.datax.admin.core.trigger.TriggerTypeEnum;
import com.wugui.datax.admin.core.util.I18nUtil;
import com.wugui.datax.admin.dto.DataXBatchJsonBuildDto;
import com.wugui.datax.admin.dto.TriggerJobDto;
import com.wugui.datax.admin.entity.JobInfo;
import com.wugui.datax.admin.mapper.JobInfoMapper;
import com.wugui.datax.admin.security.AccessControl;
import com.wugui.datax.admin.security.GlueScriptAccess;
import com.wugui.datax.admin.service.JobService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * index controller
 *
 * @author xuxueli 2015-12-19 16:13:16
 */
@Api(tags = "任务配置接口")
@RestController
@RequestMapping("/api/job")
public class JobInfoController extends BaseController{

    @Resource
    private JobService jobService;
    @Resource
    private JobInfoMapper jobInfoMapper;

    /**
     * 按 id 动一个任务之前先判归属：管理员放行，否则只能动自己的。
     *
     * @param exists 库里 load 出来的那一行（不信任请求体里的 userId，也不重新查第二遍）
     * @return 放行返回 null；拒绝返回可直接回给前端的失败体
     */
    private ReturnT<String> denyUnlessCanOperate(JobInfo exists, HttpServletRequest request) {
        if (exists == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE,
                    I18nUtil.getString("jobinfo_field_id") + I18nUtil.getString("system_not_found"));
        }
        String deny = AccessControl.denyUnlessAdminOrOwner(exists.getUserId(), getCurrentUserId(request));
        if (deny != null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, deny);
        }
        return null;
    }

    @GetMapping("/pageList")
    @ApiOperation("任务列表")
    public ReturnT<Map<String, Object>> pageList(@RequestParam(required = false, defaultValue = "0") int current,
                                        @RequestParam(required = false, defaultValue = "10") int size,
                                        int jobGroup, int triggerStatus, String jobDesc, String glueType, Integer[] projectIds) {

        return new ReturnT<>(jobService.pageList((current-1)*size, size, jobGroup, triggerStatus, jobDesc, glueType, 0, projectIds));
    }

    @GetMapping("/list")
    @ApiOperation("全部任务列表")
    public ReturnT<List<JobInfo>> list(){
        return new ReturnT<>(jobService.list());
    }

    @PostMapping("/add")
    @ApiOperation("添加任务")
    public ReturnT<String> add(HttpServletRequest request, @RequestBody JobInfo jobInfo) {
        // GLUE 任务收归管理员：判定只在 GlueScriptAccess 一处，理由见该类注释
        // （按 isScript 判会漏掉 GLUE_GROOVY —— 它在执行器 JVM 里编译执行，同样是任意代码执行）。
        String glueDeny = GlueScriptAccess.denyMessage(jobInfo.getGlueType());
        if (glueDeny != null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, glueDeny);
        }
        // JobInfo.userId 是基本类型 int，而 getCurrentUserId 在登录态失效时返回 null（批次10-D 的口径），
        // 直接传进 setUserId 就是拆箱 NPE → 500。归属判据取不到必须明确拒，
        // 不能靠异常形态"失败"，更不能默认成 0（0 在别的接口里是"管理员/全部"的语义）。
        Integer currentUserId = getCurrentUserId(request);
        if (currentUserId == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, AccessControl.NO_LOGIN_MSG);
        }
        jobInfo.setUserId(currentUserId.intValue());
        return jobService.add(jobInfo);
    }

    @PostMapping("/update")
    @ApiOperation("更新任务")
    public ReturnT<String> update(HttpServletRequest request,@RequestBody JobInfo jobInfo) {
        // GLUE 任务的更新同样收归管理员（与 add 同理）：
        // 把一个 BEAN 类型的任务改成 GLUE_SHELL / GLUE_GROOVY 再填内容，效果等于绕过 add 的守卫。
        String glueDeny = GlueScriptAccess.denyMessage(jobInfo.getGlueType());
        if (glueDeny != null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, glueDeny);
        }
        JobInfo exists = jobInfoMapper.loadById(jobInfo.getId());
        ReturnT<String> deny = denyUnlessCanOperate(exists, request);
        if (deny != null) {
            return deny;
        }
        // 属主以库里那一行为准。原先这里无条件写成调用者的 id：同事或管理员改一次配置，
        // 任务的 user_id 就跟着变成他，真正的属主反而从此失去这个任务。
        jobInfo.setUserId(exists.getUserId());
        return jobService.update(jobInfo);
    }

    @PostMapping(value = "/remove/{id}")
    @ApiOperation("移除任务")
    public ReturnT<String> remove(HttpServletRequest request, @PathVariable(value = "id") int id) {
        ReturnT<String> deny = denyUnlessCanOperate(jobInfoMapper.loadById(id), request);
        if (deny != null) {
            return deny;
        }
        return jobService.remove(id);
    }

    @RequestMapping(value = "/stop",method = RequestMethod.POST)
    @ApiOperation("停止任务")
    public ReturnT<String> pause(HttpServletRequest request, int id) {
        ReturnT<String> deny = denyUnlessCanOperate(jobInfoMapper.loadById(id), request);
        if (deny != null) {
            return deny;
        }
        return jobService.stop(id);
    }

    @RequestMapping(value = "/start",method = RequestMethod.POST)
    @ApiOperation("开启任务")
    public ReturnT<String> start(HttpServletRequest request, int id) {
        ReturnT<String> deny = denyUnlessCanOperate(jobInfoMapper.loadById(id), request);
        if (deny != null) {
            return deny;
        }
        return jobService.start(id);
    }

    @PostMapping(value = "/trigger")
    @ApiOperation("触发任务")
    public ReturnT<String> triggerJob(HttpServletRequest request, @RequestBody TriggerJobDto dto) {
        // 手动触发和"改配置"同权：不判归属的话，任何登录用户都能按 jobId 把别人的作业跑一遍，
        // 效果是直接往别人的目标表写数 —— 比改配置更直接。
        JobInfo exists = jobInfoMapper.loadById(dto.getJobId());
        ReturnT<String> deny = denyUnlessCanOperate(exists, request);
        if (deny != null) {
            return deny;
        }
        // force cover job param
        String executorParam=dto.getExecutorParam();
        if (executorParam == null) {
            executorParam = "";
        }
        JobTriggerPoolHelper.trigger(dto.getJobId(), TriggerTypeEnum.MANUAL, -1, null, executorParam);
        return ReturnT.SUCCESS;
    }

    @GetMapping("/nextTriggerTime")
    @ApiOperation("获取近5次触发时间")
    public ReturnT<List<String>> nextTriggerTime(String cron) {
        List<String> result = new ArrayList<>();
        try {
            CronExpression cronExpression = new CronExpression(cron);
            Date lastTime = new Date();
            for (int i = 0; i < 5; i++) {
                lastTime = cronExpression.getNextValidTimeAfter(lastTime);
                if (lastTime != null) {
                    result.add(DateUtil.formatDateTime(lastTime));
                } else {
                    break;
                }
            }
        } catch (ParseException e) {
            return new ReturnT<>(ReturnT.FAIL_CODE, I18nUtil.getString("jobinfo_field_cron_invalid"));
        }
        return new ReturnT<>(result);
    }

    @PostMapping("/batchAdd")
    @ApiOperation("批量创建任务")
    public ReturnT<String> batchAdd(HttpServletRequest request, @RequestBody DataXBatchJsonBuildDto dto) throws IOException {
        if (dto.getTemplateId() ==0) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("system_please_choose") + I18nUtil.getString("jobinfo_field_temp")));
        }
        // 归属必须落在调用者身上：这一条走的是模板复制，模板带着谁的 user_id，建出来的一整批就归谁。
        // 写路径已开始判归属之后，不 stamp 的后果是"普通用户批量建的任务自己改不了"。
        Integer currentUserId = getCurrentUserId(request);
        return jobService.batchAdd(dto, currentUserId == null ? 0 : currentUserId.intValue());
    }
}
