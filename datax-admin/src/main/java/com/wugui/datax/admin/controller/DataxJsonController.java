package com.wugui.datax.admin.controller;

import com.baomidou.mybatisplus.extension.api.R;
import com.wugui.datax.admin.core.util.I18nUtil;
import com.wugui.datax.admin.dto.DataXJsonBuildDto;
import com.wugui.datax.admin.security.AccessControl;
import com.wugui.datax.admin.service.DataxJsonService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Created by jingwk on 2020/05/05
 */

@RestController
@RequestMapping("api/dataxJson")
@Api(tags = "组装datax  json的控制器")
public class DataxJsonController extends BaseController {

    @Autowired
    private DataxJsonService dataxJsonService;


    /**
     * 构建 DataX 作业的 JSON 配置。
     *
     * 收归管理员：产出的 JSON 里包含数据源的明文连接口令（reader.parameter.username/password），
     * 原先任何登录用户调这个接口就能拿到全平台任意数据源的口令 —— 效果与读数据源表无异，
     * 而且连 AES 解密那层都帮用户做好了。
     */
    @PostMapping("/buildJson")
    @ApiOperation("JSON构建")
    public R<String> buildJobJson(@RequestBody DataXJsonBuildDto dto) {
        String deny = AccessControl.adminDeny();
        if (deny != null) {
            return failed(deny);
        }
        String key = "system_please_choose";
        if (dto.getReaderDatasourceId() == null) {
            return failed(I18nUtil.getString(key) + I18nUtil.getString("jobinfo_field_readerDataSource"));
        }
        if (dto.getWriterDatasourceId() == null) {
            return failed(I18nUtil.getString(key) + I18nUtil.getString("jobinfo_field_writerDataSource"));
        }
        if (CollectionUtils.isEmpty(dto.getReaderColumns())) {
            return failed(I18nUtil.getString(key) + I18nUtil.getString("jobinfo_field_readerColumns"));
        }
        if (CollectionUtils.isEmpty(dto.getWriterColumns())) {
            return failed(I18nUtil.getString(key) + I18nUtil.getString("jobinfo_field_writerColumns"));
        }
        return success(dataxJsonService.buildJobJson(dto));
    }

}
