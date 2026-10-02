package com.wugui.datax.admin.controller;

import com.baomidou.mybatisplus.extension.api.R;
import com.wugui.datax.admin.core.util.I18nUtil;
import com.wugui.datax.admin.dto.DataXJsonBuildDto;
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
     * 产出的 JSON 里<b>不含数据源账密</b>：reader/writer 的 username/password 写的是
     * {@code @@DATAX_DS_USER:<id>@@} / {@code @@DATAX_DS_PWD:<id>@@} 引用，明文只在
     * {@code JobTrigger} 派发的那一刻从库里还原（见 {@code DsSecretPlaceholder}）。
     *
     * <h2>为什么这里不能有管理员判定</h2>
     * "选数据源 → 选表 → 选列 → 生成 JSON"是普通用户建作业的主流程，也是向导唯一的一次 JSON 生成机会；
     * 这个接口一旦收归管理员，普通用户就再也建不了同步任务，而能建任务的模板/批量入口（管理员专属）
     * 又覆盖不了向导路径 —— 等于把功能关掉。所以这一面按"产出里不放凭据"收口，不按"谁能调"收口，
     * 和数据源只读接口（{@code JobDatasourceController#hideSecret}）用的是同一个口径。
     */
    @PostMapping("/buildJson")
    @ApiOperation("JSON构建")
    public R<String> buildJobJson(@RequestBody DataXJsonBuildDto dto) {
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
