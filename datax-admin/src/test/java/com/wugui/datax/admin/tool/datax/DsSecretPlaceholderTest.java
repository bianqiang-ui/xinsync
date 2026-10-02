package com.wugui.datax.admin.tool.datax;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.api.R;
import com.wugui.datax.admin.controller.DataxJsonController;
import com.wugui.datax.admin.dto.DataXJsonBuildDto;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.service.DataxJsonService;
import com.wugui.datax.admin.tool.datax.reader.MongoDBReader;
import com.wugui.datax.admin.tool.datax.reader.MysqlReader;
import com.wugui.datax.admin.tool.datax.writer.MongoDBWriter;
import com.wugui.datax.admin.tool.datax.writer.MysqlWriter;
import com.wugui.datax.admin.tool.pojo.DataxMongoDBPojo;
import com.wugui.datax.admin.tool.pojo.DataxRdbmsPojo;
import com.wugui.datax.admin.util.JSONUtils;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * job_json 只带数据源引用，不带账密（批次 10-J 的收口方式）。
 *
 * <h2>钉住的两件事</h2>
 * 1) 生成面：reader/writer 建出来的 JSON 里，username/password 是 {@code @@DATAX_DS_*@@} 引用，
 * 真实口令一个字节都不出现 —— 这样 {@code /api/dataxJson/buildJson} 的响应体、
 * {@code job_info.job_json} 的落库值、模板里的值，三面同时干净。
 * 2) 主流程不能被砍：建作业向导是普通用户唯一能生成 JSON 的路径，所以第 10 条反向钉住
 * "普通用户调 buildJson 必须成功"。上一版用"这个接口只给管理员"来堵泄漏，把这条主流程整个关掉了。
 *
 * <h2>还原面为什么也要在这里测</h2>
 * 占位符只在派发那一刻换成明文。换不回来时必须失败得看得见（任务未下发 + 说清是哪一行数据源），
 * 不能静默把一个还带着 {@code @@...@@} 的 JSON 发给执行器 —— 那等于让 DataX 拿占位符去连库，
 * 报错会落在"目标库认证失败"上，排查方向整个被带偏。
 */
public class DsSecretPlaceholderTest {

    private static final String PWD_7 = "s3cr3t-!7";
    private static final String USER_7 = "datax_ro";

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static JobDatasource ds(Long id, String username, String password) {
        JobDatasource datasource = new JobDatasource();
        datasource.setId(id);
        datasource.setDatasource("mysql");
        datasource.setDatasourceName("源" + id);
        datasource.setJdbcUsername(username);
        datasource.setJdbcPassword(password);
        datasource.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/datax_web");
        datasource.setDatabaseName("datax_web");
        return datasource;
    }

    private static DataxRdbmsPojo rdbmsPojo(JobDatasource datasource) {
        DataxRdbmsPojo pojo = new DataxRdbmsPojo();
        pojo.setJobDatasource(datasource);
        pojo.setTables(new ArrayList<String>(Collections.singletonList("t_user")));
        pojo.setRdbmsColumns(new ArrayList<String>(Collections.singletonList("`id`")));
        pojo.setSplitPk("id");
        return pojo;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameterOf(Map<String, Object> built) {
        return (Map<String, Object>) built.get("parameter");
    }

    // ---------- 生成面 ----------

    @Test
    public void mysqlReaderEmitsReferenceNotCredential() {
        Map<String, Object> reader = new MysqlReader().build(rdbmsPojo(ds(7L, USER_7, PWD_7)));
        Map<String, Object> parameter = parameterOf(reader);

        assertEquals(DsSecretPlaceholder.userToken(7L), parameter.get("username"));
        assertEquals(DsSecretPlaceholder.pwdToken(7L), parameter.get("password"));
        assertFalse("真实口令不能出现在生成的 JSON 里", JSON.toJSONString(reader).contains(PWD_7));
        assertFalse("用户名同样不外发（AES 默认密钥写在本仓库里，密文≈明文）",
                JSON.toJSONString(reader).contains(USER_7));
    }

    @Test
    public void mysqlWriterEmitsReferenceNotCredential() {
        Map<String, Object> writer = new MysqlWriter().build(rdbmsPojo(ds(8L, "app", "app-pwd")));
        Map<String, Object> parameter = parameterOf(writer);

        assertEquals(DsSecretPlaceholder.userToken(8L), parameter.get("username"));
        assertEquals(DsSecretPlaceholder.pwdToken(8L), parameter.get("password"));
        assertFalse(JSON.toJSONString(writer).contains("app-pwd"));
    }

    /**
     * 免认证实例（MySQL 空口令、Mongo 无鉴权）必须照常生成，不能被占位符逻辑改坏。
     */
    @Test
    public void blankCredentialKeepsOldOutput() {
        Map<String, Object> reader = parameterOf(new MysqlReader().build(rdbmsPojo(ds(9L, "", ""))));
        assertEquals("", reader.get("username"));
        assertEquals("", reader.get("password"));

        Map<String, Object> noId = parameterOf(new MysqlReader().build(rdbmsPojo(ds(null, USER_7, PWD_7))));
        assertEquals("数据源对象没有 id 时无处可还原，只能照原样写（保持改动前行为）", USER_7, noId.get("username"));
        assertEquals(PWD_7, noId.get("password"));
    }

    @Test
    public void mongoReaderAndWriterEmitReferenceNotCredential() {
        DataxMongoDBPojo pojo = new DataxMongoDBPojo();
        JobDatasource mongo = ds(11L, "mongoUser", "mongoPwd");
        mongo.setDatasource("mongodb");
        mongo.setJdbcUrl("mongodb://127.0.0.1:27017/admin");
        pojo.setJdbcDatasource(mongo);
        pojo.setColumns(new ArrayList<Map<String, Object>>(
                Collections.singletonList(Collections.<String, Object>singletonMap("name", "id"))));
        pojo.setReaderTable("t_user");
        pojo.setWriterTable("t_user");

        Map<String, Object> reader = parameterOf(new MongoDBReader().buildMongoDB(pojo));
        Map<String, Object> writer = parameterOf(new MongoDBWriter().buildMongoDB(pojo));

        // Mongo 用的是 userName / userPassword 两个键名，还原按值匹配，与键名无关
        assertEquals(DsSecretPlaceholder.userToken(11L), reader.get("userName"));
        assertEquals(DsSecretPlaceholder.pwdToken(11L), reader.get("userPassword"));
        assertEquals(DsSecretPlaceholder.userToken(11L), writer.get("userName"));
        assertEquals(DsSecretPlaceholder.pwdToken(11L), writer.get("userPassword"));
        assertFalse(JSON.toJSONString(reader).contains("mongoPwd"));
    }

    // ---------- 还原面 ----------

    private static String jobJsonWith(String readerUser, String readerPwd, String writerUser, String writerPwd) {
        return "{\"job\":{\"content\":[{\"reader\":{\"name\":\"mysqlreader\",\"parameter\":{\"username\":\""
                + readerUser + "\",\"password\":\"" + readerPwd + "\"}},"
                + "\"writer\":{\"name\":\"mysqlwriter\",\"parameter\":{\"username\":\"" + writerUser
                + "\",\"password\":\"" + writerPwd + "\"}}}]}}";
    }

    /** 库里取回来的那一行仍是密文口径（select 不带 typeHandler），这里用"解不开就用原值"那条兜底。 */
    private static LongFunction<JobDatasource> loaderReturning(Map<Long, JobDatasource> rows, AtomicInteger hits) {
        return id -> {
            if (hits != null) {
                hits.incrementAndGet();
            }
            return rows.get(id);
        };
    }

    @Test
    public void legacyJsonWithoutReferenceIsUntouched() {
        // 存量任务的 job_json 里是密文，走 JSONUtils 那条既有解密路径；本类必须原样放行
        String legacy = jobJsonWith("Zm9vYmFy", "eW91", "Zm9vYmFy", "eW91");
        assertSame("不含占位符时必须把入参原样退回，不能重新序列化一遍",
                legacy, DsSecretPlaceholder.resolve(legacy, id -> ds(id, "x", "y")));
    }

    @Test
    public void noReferenceMeansNoDatabaseHit() {
        AtomicInteger hits = new AtomicInteger();
        DsSecretPlaceholder.resolve("{\"job\":{}}", loaderReturning(new HashMap<Long, JobDatasource>(), hits));
        assertEquals("还原逻辑不能对每个任务都多查一次库", 0, hits.get());
    }

    @Test
    public void referencesAreReplacedWithRealValues() {
        Map<Long, JobDatasource> rows = new HashMap<Long, JobDatasource>();
        rows.put(7L, ds(7L, USER_7, PWD_7));
        rows.put(8L, ds(8L, "app", "app-pwd"));
        String json = jobJsonWith(DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L),
                DsSecretPlaceholder.userToken(8L), DsSecretPlaceholder.pwdToken(8L));

        String resolved = DsSecretPlaceholder.resolve(json, loaderReturning(rows, null));

        assertFalse("还原后不能再有占位符残留", resolved.contains(DsSecretPlaceholder.TOKEN_PREFIX));
        JSONObject parameter = firstContent(resolved).getJSONObject("reader").getJSONObject("parameter");
        assertEquals(USER_7, parameter.getString("username"));
        assertEquals(PWD_7, parameter.getString("password"));
        assertEquals("app-pwd", firstContent(resolved).getJSONObject("writer")
                .getJSONObject("parameter").getString("password"));
    }

    /** reader 和 writer 常常是同一个源：查库要合并成一次。 */
    @Test
    public void sameDatasourceIsLoadedOnce() {
        AtomicInteger hits = new AtomicInteger();
        Map<Long, JobDatasource> rows = new HashMap<Long, JobDatasource>();
        rows.put(7L, ds(7L, USER_7, PWD_7));
        String json = jobJsonWith(DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L),
                DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L));

        DsSecretPlaceholder.resolve(json, loaderReturning(rows, hits));

        assertEquals(1, hits.get());
    }

    /**
     * 口令里有引号、反斜杠、美元符时，替换必须仍然是合法的 JSON。
     * 这是"按字符串正则替换"这种写法最容易翻车的地方（$ 和 \ 在 replace 里有特殊含义）。
     */
    @Test
    public void nastyPasswordStillProducesValidJson() {
        String nasty = "a\"b\\c$d$1$g${x}";
        Map<Long, JobDatasource> rows = new HashMap<Long, JobDatasource>();
        rows.put(7L, ds(7L, USER_7, nasty));
        String json = jobJsonWith(DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L),
                DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L));

        String resolved = DsSecretPlaceholder.resolve(json, loaderReturning(rows, null));

        assertEquals(nasty, firstContent(resolved).getJSONObject("reader")
                .getJSONObject("parameter").getString("password"));
    }

    @Test
    public void deletedDatasourceFailsVisibly() {
        String json = jobJsonWith(DsSecretPlaceholder.userToken(404L), DsSecretPlaceholder.pwdToken(404L),
                DsSecretPlaceholder.userToken(404L), DsSecretPlaceholder.pwdToken(404L));
        try {
            DsSecretPlaceholder.resolve(json, id -> null);
            fail("数据源已被删除时必须抛出，不能让占位符原样发给执行器");
        } catch (IllegalStateException e) {
            assertTrue("失败原因要点名是哪一行数据源：" + e.getMessage(), e.getMessage().contains("404"));
        }
    }

    @Test
    public void clearedCredentialFailsVisibly() {
        Map<Long, JobDatasource> rows = new HashMap<Long, JobDatasource>();
        rows.put(7L, ds(7L, USER_7, null));
        String json = jobJsonWith(DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L),
                DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L));
        try {
            DsSecretPlaceholder.resolve(json, loaderReturning(rows, null));
            fail("建任务之后口令被清空 = 拿着空串去连库，必须在这里失败");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("已被清空"));
        }
    }

    /**
     * 新任务（占位符）也要先过一遍 {@code JSONUtils.changeJson} —— 派发时那一步是历史必经的，
     * 它把解不开的密文原样退回（{@code AESUtil.decrypt} 失败即 null），所以占位符不会被它吃掉。
     * 这条钉的是两条路径的先后顺序，不是 changeJson 本身。
     */
    @Test
    public void jsonUtilsPassesReferenceThrough() {
        String json = jobJsonWith(DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L),
                DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L));

        String afterChange = JSONUtils.changeJson(json, JSONUtils.decrypt);

        assertTrue("changeJson 不能把占位符变成空串：" + afterChange,
                afterChange.contains(DsSecretPlaceholder.pwdToken(7L)));
        Map<Long, JobDatasource> rows = new HashMap<Long, JobDatasource>();
        rows.put(7L, ds(7L, USER_7, PWD_7));
        assertEquals(PWD_7, firstContent(DsSecretPlaceholder.resolve(afterChange, loaderReturning(rows, null)))
                .getJSONObject("writer").getJSONObject("parameter").getString("password"));
    }

    // ---------- 反向红线：向导不能被权限关掉 ----------

    /**
     * 普通用户走完"选源→选列→生成 JSON"是这条链路存在的意义。
     * 上一版把 buildJson 收归管理员，于是普通用户建不了任何同步任务（这一条就是这个回退的哨兵）。
     */
    @Test
    public void normalUserCanStillBuildJson() {
        loginAs("alice", "0");
        DataxJsonController controller = new DataxJsonController();
        DataxJsonService service = Mockito.mock(DataxJsonService.class);
        ReflectionTestUtils.setField(controller, "dataxJsonService", service);
        String built = jobJsonWith(DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L),
                DsSecretPlaceholder.userToken(7L), DsSecretPlaceholder.pwdToken(7L));
        when(service.buildJobJson(any(DataXJsonBuildDto.class))).thenReturn(built);

        R<String> result = controller.buildJobJson(fullDto());

        assertTrue("普通用户的建作业向导必须照常可用：" + result.getMsg(), result.ok());
        assertSame(built, result.getData());
        assertFalse("向导产物里不能有真实口令", result.getData().contains(PWD_7));
    }

    /** 管理员当然也走得通 —— 收口方式换了，能力不能缩水。 */
    @Test
    public void adminCanStillBuildJson() {
        loginAs("admin", "ROLE_ADMIN");
        DataxJsonController controller = new DataxJsonController();
        DataxJsonService service = Mockito.mock(DataxJsonService.class);
        ReflectionTestUtils.setField(controller, "dataxJsonService", service);
        when(service.buildJobJson(any(DataXJsonBuildDto.class))).thenReturn("{}");

        assertTrue(controller.buildJobJson(fullDto()).ok());
    }

    private static DataXJsonBuildDto fullDto() {
        DataXJsonBuildDto dto = new DataXJsonBuildDto();
        dto.setReaderDatasourceId(7L);
        dto.setWriterDatasourceId(7L);
        dto.setReaderColumns(new ArrayList<String>(Collections.singletonList("id")));
        dto.setWriterColumns(new ArrayList<String>(Collections.singletonList("id")));
        return dto;
    }

    private static void loginAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "x",
                        Collections.singletonList(new SimpleGrantedAuthority(role))));
    }

    private static JSONObject firstContent(String json) {
        JSONArray content = JSON.parseObject(json).getJSONObject("job").getJSONArray("content");
        return content.getJSONObject(0);
    }

    /** 防呆：token 形状是 Java 侧与门禁脚本共同的口径，改了必须两边一起改。 */
    @Test
    public void tokenShapeIsStable() {
        assertEquals("@@DATAX_DS_USER:7@@", DsSecretPlaceholder.userToken(7L));
        assertEquals("@@DATAX_DS_PWD:7@@", DsSecretPlaceholder.pwdToken(7L));
        assertEquals("@@DATAX_DS_", DsSecretPlaceholder.TOKEN_PREFIX);
    }
}
