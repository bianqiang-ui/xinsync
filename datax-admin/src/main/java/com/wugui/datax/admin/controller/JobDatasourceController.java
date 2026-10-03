package com.wugui.datax.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.api.R;
import com.wugui.datax.admin.core.util.LocalCacheUtil;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.security.AccessControl;
import com.wugui.datax.admin.service.JobDatasourceService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

/**
 * jdbc数据源配置控制器层
 *
 * @author zhouhongfa@gz-yibo.com
 * @version v1.0
 * @since 2019-07-30
 */
@RestController
@RequestMapping("/api/jobJdbcDatasource")
@Api(tags = "jdbc数据源配置接口")
public class JobDatasourceController extends BaseController {
    /**
     * 服务对象
     */
    @Autowired
    private JobDatasourceService jobJdbcDatasourceService;

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
    public R<IPage<JobDatasource>> selectAll() {
        BaseForm form = new BaseForm();
        QueryWrapper<JobDatasource> query = (QueryWrapper<JobDatasource>) form.pageQueryWrapperCustom(form.getParameters(), new QueryWrapper<JobDatasource>());
        IPage<JobDatasource> page = jobJdbcDatasourceService.page(form.getPlusPagingQueryEntity(), query);
        if (page != null && page.getRecords() != null) {
            page.getRecords().forEach(JobDatasourceController::hideSecret);
        }
        return success(page);
    }

    /**
     * 获取所有数据源
     * @return
     */
    @ApiOperation("获取所有数据源")
    @GetMapping("/all")
    public R<List<JobDatasource>> selectAllDatasource() {
        List<JobDatasource> list = this.jobJdbcDatasourceService.selectAllDatasource();
        if (list != null) {
            list.forEach(JobDatasourceController::hideSecret);
        }
        return success(list);
    }

    /**
     * 通过主键查询单条数据
     *
     * @param id 主键
     * @return 单条数据
     */
    @ApiOperation("通过主键查询单条数据")
    @GetMapping("{id}")
    public R<JobDatasource> selectOne(@PathVariable Serializable id) {
        return success(hideSecret(this.jobJdbcDatasourceService.getById(id)));
    }

    /**
     * 读出口回给前端的口令占位值。<b>固定 6 个星号</b>，与库里真实口令的长度、内容都无关。
     *
     * <h2>为什么不能直接回空</h2>
     * 前端（打包产物 {@code static/static/js/chunk-60797987.*.js}）把 {@code jdbcPassword} 定成必填项
     * （{@code jdbcPassword:[{required:!0,...}]}）。回空的话，管理员打开"编辑数据源"改任何一个别的字段，
     * 点保存都会被表单卡在 "this is required"，请求根本发不出去 —— 那是把功能修坏，不是加固。
     * 回固定掩码同时解决两件事：必填校验过得去、真实值一个字节都不出去，
     * 而且"用户没动这一栏"变得可识别（回提值 == 掩码 ⇒ 不修改）。
     *
     * <h2>代价（写进升级说明）</h2>
     * 真口令恰好是 {@code ******} 的数据源会被判成"未修改"——这种口令等于没设，换一个即可，不提供绕过开关。
     */
    private static final String PASSWORD_MASK = "******";

    /**
     * 读接口一律不回传数据源口令，只回 {@link #PASSWORD_MASK}。
     *
     * <h2>为什么"只是密文"也算泄漏</h2>
     * {@code jdbc_password} 列上的 {@code AESEncryptHandler} 只作用在<b>写入</b>侧：
     * MP 生成的 select 要用 {@code @TableName(autoResultMap = true)} 才会带上字段 typeHandler，
     * 本实体没开，所以查出来的仍是库里的密文（这也是 {@code BaseQueryTool}/{@code JSONUtils} 自己调
     * {@code AESUtil.decrypt} 的原因）。而 {@code datasource.aes.key} 有<b>出厂默认值并且写在仓库里</b>，
     * 拿到密文与拿到明文没有区别 —— 只多了"读一次源码"这一步，而源码是公开的。
     *
     * <h2>为什么只能在 controller 剥、service 的 getById 必须继续带</h2>
     * 下面的 {@code update()} 要用库里那条旧值认"旧前端原样回提的密文"；service 一起剥掉的话比较永远不成立。
     *
     * <h2>为什么不用"改管理员专属"来收口</h2>
     * 普通用户建作业时要选数据源，这三个读接口必须对全体登录用户开放（见 CHANGELOG 的 P6 待拍板项）。
     * 所以收口点在字段上，不在入口上。
     */
    private static JobDatasource hideSecret(JobDatasource datasource) {
        if (datasource != null) {
            datasource.setJdbcPassword(PASSWORD_MASK);
        }
        return datasource;
    }

    /**
     * 新增数据
     *
     * @param entity 实体对象
     * @return 新增结果
     */
    @ApiOperation("新增数据")
    @PostMapping
    public R<Boolean> insert(@RequestBody JobDatasource entity) {
        // 数据源没有属主列（job_datasource 无 user_id），是全平台共享的基础设施：
        // 建一个源 = 让调度器往后朝这个地址连库，所以写操作管理员专属；只读接口不动。
        String deny = AccessControl.adminDeny();
        if (deny != null) {
            return failed(deny);
        }
        return success(this.jobJdbcDatasourceService.save(entity));
    }

    /**
     * 修改数据
     *
     * @param entity 实体对象
     * @return 修改结果
     */
    @PutMapping
    @ApiOperation("修改数据")
    public R<Boolean> update(@RequestBody JobDatasource entity) {
        String deny = AccessControl.adminDeny();
        if (deny != null) {
            return failed(deny);
        }
        LocalCacheUtil.remove(entity.getDatasourceName());
        JobDatasource d = jobJdbcDatasourceService.getById(entity.getId());
        if (d == null) {
            return failed("数据源不存在，id = " + entity.getId());
        }
        // 更新接口允许只提交部分字段，所以比较要拿库里的值做主语：
        // 原先写成 entity.getJdbcUsername().equals(...)，前端不传 jdbcUsername 时直接 NPE 成 500
        if (null != d.getJdbcUsername() && d.getJdbcUsername().equals(entity.getJdbcUsername())) {
            entity.setJdbcUsername(null);
        }
        // 这三种提交都解释成"这次不改口令"，而不是"把口令清空"：
        //   1) 掩码 —— 读接口回给前端的占位值，用户没动这一栏时回提的就是它；
        //   2) 空白 —— 非浏览器调用方（脚本、老前端）不带这一栏；
        //   3) 与库里那条完全相同 —— 历史行为，旧前端会回原样（改动前是密文）。
        // 若照原样更新，一次"只改数据源名称"的编辑就会把这个源的连接口令抹掉，
        // 而失败要到下一次作业触发连库时才暴露——离编辑动作十万八千里，没人会想到是这儿。
        // 代价：编辑界面无法把口令"清空"（无口令的源新建时就留空；要换口令就提交新值）。
        if (StringUtils.isBlank(entity.getJdbcPassword())
                || PASSWORD_MASK.equals(entity.getJdbcPassword())
                || entity.getJdbcPassword().equals(d.getJdbcPassword())) {
            entity.setJdbcPassword(null);
        }
        return success(this.jobJdbcDatasourceService.updateById(entity));
    }

    /**
     * 删除数据
     *
     * @param idList 主键结合
     * @return 删除结果
     */
    @DeleteMapping
    @ApiOperation("删除数据")
    public R<Boolean> delete(@RequestParam("idList") List<Long> idList) {
        String deny = AccessControl.adminDeny();
        if (deny != null) {
            return failed(deny);
        }
        return success(this.jobJdbcDatasourceService.removeByIds(idList));
    }

    /**
     * 测试数据源
     * @param jobJdbcDatasource
     * @return
     */
    @PostMapping("/test")
    @ApiOperation("测试数据")
    public R<Boolean> dataSourceTest (@RequestBody JobDatasource jobJdbcDatasource) throws IOException {
        // 这个接口会拿请求体里的 jdbcUrl 真的去建连接，等于一个任意地址探测面（SSRF）。
        // 数据源本身是平台级资源，连"试连"也收归管理员；前端建源流程本来就在管理员页面里。
        String deny = AccessControl.adminDeny();
        if (deny != null) {
            return failed(deny);
        }
        return success(jobJdbcDatasourceService.dataSourceTest(jobJdbcDatasource));
    }
}