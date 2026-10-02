package com.wugui.datax.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datax.admin.core.util.I18nUtil;
import com.wugui.datax.admin.entity.JobUser;
import com.wugui.datax.admin.mapper.JobUserMapper;
import com.wugui.datax.admin.security.AccessControl;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.wugui.datatx.core.biz.model.ReturnT.FAIL_CODE;

/**
 * Created by jingwk on 2019/11/17
 */
@RestController
@RequestMapping("/api/user")
@Api(tags = "用户信息接口")
public class UserController {

    @Resource
    private JobUserMapper jobUserMapper;

    @Resource
    private BCryptPasswordEncoder bCryptPasswordEncoder;

    /**
     * 用户名只允许字母、数字与 . _ - @，从源头阻断存储型 XSS 载荷（issue #652）
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9._@\\-]{4,20}$");

    private static final String ILLEGAL_USERNAME_MSG = "用户名只能包含字母、数字和 . _ - @ ，长度 4-20";

    private JobUser hidePassword(JobUser jobUser) {
        if (jobUser != null) {
            jobUser.setPassword(null);
        }
        return jobUser;
    }

    @GetMapping("/pageList")
    @ApiOperation("用户列表")
    public ReturnT<Map<String, Object>> pageList(@RequestParam(required = false, defaultValue = "1") int current,
                                                 @RequestParam(required = false, defaultValue = "10") int size,
                                                 String username) {

        // page list
        List<JobUser> list = jobUserMapper.pageList((current - 1) * size, size, username);
        int recordsTotal = jobUserMapper.pageListCount((current - 1) * size, size, username);

        // package result
        Map<String, Object> maps = new HashMap<>();
        maps.put("recordsTotal", recordsTotal);        // 总记录数
        maps.put("recordsFiltered", recordsTotal);    // 过滤后的总记录数
        maps.put("data", list.stream().map(this::hidePassword).collect(Collectors.toList()));                    // 分页列表
        return new ReturnT<>(maps);
    }

    @GetMapping("/list")
    @ApiOperation("用户列表")
    public ReturnT<List<JobUser>> list(String username) {

        // page list
        List<JobUser> list = jobUserMapper.findAll(username);
        return new ReturnT<>(list.stream().map(this::hidePassword).collect(Collectors.toList()));
    }

    @GetMapping("/getUserById")
    @ApiOperation(value = "根据id获取用户")
    public ReturnT<JobUser> selectById(@RequestParam("userId") Integer userId) {
        return new ReturnT<>(hidePassword(jobUserMapper.getUserById(userId)));
    }

    @PostMapping("/add")
    @ApiOperation("添加用户")
    public ReturnT<String> add(@RequestBody JobUser jobUser) {

        ReturnT<String> denied = AccessControl.requireAdmin();
        if (denied != null) {
            return denied;
        }
        // valid username
        if (!StringUtils.hasText(jobUser.getUsername())) {
            return new ReturnT<>(FAIL_CODE, I18nUtil.getString("system_please_input") + I18nUtil.getString("user_username"));
        }
        jobUser.setUsername(jobUser.getUsername().trim());
        if (!USERNAME_PATTERN.matcher(jobUser.getUsername()).matches()) {
            return new ReturnT<>(FAIL_CODE, ILLEGAL_USERNAME_MSG);
        }
        // valid password
        if (!StringUtils.hasText(jobUser.getPassword())) {
            return new ReturnT<>(FAIL_CODE, I18nUtil.getString("system_please_input") + I18nUtil.getString("user_password"));
        }
        jobUser.setPassword(jobUser.getPassword().trim());
        if (!(jobUser.getPassword().length() >= 4 && jobUser.getPassword().length() <= 20)) {
            return new ReturnT<>(FAIL_CODE, I18nUtil.getString("system_length_limit") + "[4-20]");
        }
        jobUser.setPassword(bCryptPasswordEncoder.encode(jobUser.getPassword()));


        // check repeat
        JobUser existUser = jobUserMapper.loadByUserName(jobUser.getUsername());
        if (existUser != null) {
            return new ReturnT<>(FAIL_CODE, I18nUtil.getString("user_username_repeat"));
        }

        // write
        jobUserMapper.save(jobUser);
        return ReturnT.SUCCESS;
    }

    @PostMapping(value = "/update")
    @ApiOperation("更新用户信息")
    public ReturnT<String> update(@RequestBody JobUser jobUser) {
        ReturnT<String> denied = AccessControl.requireAdmin();
        if (denied != null) {
            return denied;
        }
        if (StringUtils.hasText(jobUser.getUsername())) {
            String username = jobUser.getUsername().trim();
            if (!USERNAME_PATTERN.matcher(username).matches()) {
                return new ReturnT<>(FAIL_CODE, ILLEGAL_USERNAME_MSG);
            }
            jobUser.setUsername(username);
        }
        if (StringUtils.hasText(jobUser.getPassword())) {
            String pwd = jobUser.getPassword().trim();
            if (StrUtil.isBlank(pwd)) {
                return new ReturnT<>(FAIL_CODE, I18nUtil.getString("system_no_blank") + "密码");
            }

            if (!(pwd.length() >= 4 && pwd.length() <= 20)) {
                return new ReturnT<>(FAIL_CODE, I18nUtil.getString("system_length_limit") + "[4-20]");
            }
            jobUser.setPassword(bCryptPasswordEncoder.encode(pwd));
        } else {
            return new ReturnT<>(FAIL_CODE, I18nUtil.getString("system_no_blank") + "密码");
        }
        // write
        jobUserMapper.update(jobUser);
        return ReturnT.SUCCESS;
    }

    @RequestMapping(value = "/remove", method = RequestMethod.POST)
    @ApiOperation("删除用户")
    public ReturnT<String> remove(int id) {
        ReturnT<String> denied = AccessControl.requireAdmin();
        if (denied != null) {
            return denied;
        }
        int result = jobUserMapper.delete(id);
        return result != 1 ? ReturnT.FAIL : ReturnT.SUCCESS;
    }

    @PostMapping(value = "/updatePwd")
    @ApiOperation("修改密码")
    public ReturnT<String> updatePwd(@RequestBody JobUser jobUser) {
        // 这个接口原先只按请求体里的 username 定位用户，任何登录用户都能把 admin 的密码改掉
        ReturnT<String> denied = AccessControl.requireSelfOrAdmin(jobUser.getUsername());
        if (denied != null) {
            return denied;
        }
        String password = jobUser.getPassword();
        if (password == null || password.trim().length() == 0) {
            return new ReturnT<>(ReturnT.FAIL.getCode(), "密码不可为空");
        }
        password = password.trim();
        if (!(password.length() >= 4 && password.length() <= 20)) {
            return new ReturnT<>(FAIL_CODE, I18nUtil.getString("system_length_limit") + "[4-20]");
        }
        // do write
        if (!USERNAME_PATTERN.matcher(StrUtil.nullToEmpty(jobUser.getUsername()).trim()).matches()) {
            return new ReturnT<>(FAIL_CODE, ILLEGAL_USERNAME_MSG);
        }
        JobUser existUser = jobUserMapper.loadByUserName(jobUser.getUsername().trim());
        if (existUser == null) {
            return new ReturnT<>(FAIL_CODE, "用户不存在");
        }
        existUser.setPassword(bCryptPasswordEncoder.encode(password));
        jobUserMapper.update(existUser);
        return ReturnT.SUCCESS;
    }

}
