package com.wugui.datax.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.api.R;
import com.wugui.datax.admin.entity.JobRegistry;
import com.wugui.datax.admin.service.JobRegistryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Created by jingwk on 2019/11/17
 */
@RestController
@RequestMapping("/api/jobRegistry")
@Api(tags = "执行器资源监控")
public class JobRegistryController extends BaseController {

	@Autowired
	private JobRegistryService jobRegistryService;

	/**
	 * 分页查询所有数据
	 *
	 * @return 所有数据
	 */
	@GetMapping
	@ApiOperation("分页查询所有数据")
	@ApiImplicitParams(
			{@ApiImplicitParam(paramType = "query", dataType = "String", name = "current", value = "当前页", defaultValue = "1", required = true),
					@ApiImplicitParam(paramType = "query", dataType = "String", name = "size", value = "一页大小", defaultValue = "10", required = true),
					@ApiImplicitParam(paramType = "query", dataType = "Boolean", name = "ifCount", value = "是否查询总数", defaultValue = "true"),
					@ApiImplicitParam(paramType = "query", dataType = "String", name = "ascs", value = "升序字段，多个用逗号分隔"),
					@ApiImplicitParam(paramType = "query", dataType = "String", name = "descs", value = "降序字段，多个用逗号分隔")
			})
	public R<IPage<JobRegistry>> selectAll() {
		BaseForm baseForm = new BaseForm();
		// 原先这里把 BaseForm 的组装逻辑抄了一遍，于是排序字段的白名单也得抄两遍：
		// 收敛到同一个实现处，注入判定只有一处可改。
		QueryWrapper<JobRegistry> query = (QueryWrapper<JobRegistry>) baseForm.pageQueryWrapperCustom(
				baseForm.getParameters(), new QueryWrapper<JobRegistry>());
		return success(this.jobRegistryService.page(baseForm.getPlusPagingQueryEntity(), query));
	}
}

