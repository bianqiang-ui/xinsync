package com.wugui.datax.admin.controller;


import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datatx.core.util.DateUtil;
import com.wugui.datax.admin.core.cron.CronExpression;
import com.wugui.datax.admin.core.util.I18nUtil;
import com.wugui.datax.admin.entity.JobTemplate;
import com.wugui.datax.admin.mapper.JobTemplateMapper;
import com.wugui.datax.admin.security.AccessControl;
import com.wugui.datax.admin.security.GlueScriptAccess;
import com.wugui.datax.admin.service.JobTemplateService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * template controller
 *
 * @author jingwk 2019-12-22 16:13:16
 */
@Api(tags = "任务配置接口")
@RestController
@RequestMapping("/api/jobTemplate")
public class JobTemplateController extends BaseController{

    @Resource
    private JobTemplateService jobTemplateService;
    @Resource
    private JobTemplateMapper jobTemplateMapper;

    /**
     * 模板是建任务的底稿，改坏一次会污染后续所有由它建出来的任务，所以按 id 操作前先判归属：
     * 管理员放行，否则只能动自己名下的。
     *
     * @return 放行返回 null；拒绝返回可直接回给前端的失败体
     */
    private ReturnT<String> denyUnlessCanOperate(JobTemplate exists, HttpServletRequest request) {
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
    @ApiOperation("任务模板列表")
    public ReturnT<Map<String, Object>> pageList(@RequestParam(required = false, defaultValue = "0") int current,
                                        @RequestParam(required = false, defaultValue = "10") int size,
                                        int jobGroup, String jobDesc, String executorHandler, int userId,Integer[] projectIds) {

        return new ReturnT<>(jobTemplateService.pageList((current-1)*size, size, jobGroup, jobDesc, executorHandler, userId, projectIds));
    }

    @PostMapping("/add")
    @ApiOperation("添加任务模板")
    public ReturnT<String> add(HttpServletRequest request, @RequestBody JobTemplate jobTemplate) {
        // 模板的 glueType/glueSource 会被 /api/job/batchAdd 的 copyProperties 原样拷进新建任务，
        // 于是"建一个 GLUE 模板 + 批量一次"就是普通用户落脚本任务的另一条路。
        // 模板页只提供 BEAN（前端 glueTypes 列表里只有 BEAN 一项），这条判定不会挡住正常用法。
        String glueDeny = GlueScriptAccess.denyMessage(jobTemplate.getGlueType());
        if (glueDeny != null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, glueDeny);
        }
        // 同 JobInfoController#add：JobTemplate.userId 是 int，null 拆箱就是 500；
        // 批量建任务会按模板的 user_id 落归属（batchAdd），模板没有属主会让整批任务无主。
        Integer currentUserId = getCurrentUserId(request);
        if (currentUserId == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, AccessControl.NO_LOGIN_MSG);
        }
        jobTemplate.setUserId(currentUserId.intValue());
        return jobTemplateService.add(jobTemplate);
    }

    @PostMapping("/update")
    @ApiOperation("更新任务")
    public ReturnT<String> update(HttpServletRequest request,@RequestBody JobTemplate jobTemplate) {
        JobTemplate exists = jobTemplateMapper.loadById(jobTemplate.getId());
        ReturnT<String> deny = denyUnlessCanOperate(exists, request);
        if (deny != null) {
            return deny;
        }
        // 属主以库里那一行为准：原先无条件写成调用者的 id，别人改一次就把模板变成自己的
        jobTemplate.setUserId(exists.getUserId());
        return jobTemplateService.update(jobTemplate);
    }

    @PostMapping(value = "/remove/{id}")
    @ApiOperation("移除任务模板")
    public ReturnT<String> remove(HttpServletRequest request, @PathVariable(value = "id") int id) {
        ReturnT<String> deny = denyUnlessCanOperate(jobTemplateMapper.loadById(id), request);
        if (deny != null) {
            return deny;
        }
        return jobTemplateService.remove(id);
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
}
