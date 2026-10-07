package com.wugui.datax.admin.core.conf;

import com.wugui.datax.admin.core.scheduler.JobScheduler;
import com.wugui.datax.admin.entity.JobUser;
import com.wugui.datax.admin.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;

/**
 * xxl-job config
 *
 * @author xuxueli 2017-04-28
 */

@Component
public class JobAdminConfig implements InitializingBean, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(JobAdminConfig.class);

    private static JobAdminConfig adminConfig = null;

    public static JobAdminConfig getAdminConfig() {
        return adminConfig;
    }


    // ---------------------- XxlJobScheduler ----------------------

    private JobScheduler xxlJobScheduler;

    @Override
    public void afterPropertiesSet() throws Exception {
        adminConfig = this;

        if (accessToken == null || accessToken.trim().length() == 0) {
            if (allowEmptyAccessToken) {
                logger.warn("datax.job.accessToken is empty and anonymous executor callbacks are explicitly allowed "
                        + "(datax.job.allowEmptyAccessToken=true). Not recommended for production.");
            } else {
                logger.error("datax.job.accessToken is empty, so /api/callback|processCallback|registry|registryRemove "
                        + "will reject every request. Configure DATAX_ACCESS_TOKEN on both admin and executor, "
                        + "or set datax.job.allowEmptyAccessToken=true to keep the legacy behaviour.");
            }
        }

        if (DEFAULT_DATASOURCE_AES_KEY.equals(dataSourceAESKey)) {
            logger.error("datasource.aes.key is still the shipped default, data source passwords can be decrypted by "
                    + "anyone who reads the code. Set DATAX_AES_KEY and re-enter the data sources.");
        }

        checkDefaultAdminPassword();

        xxlJobScheduler = new JobScheduler();
        xxlJobScheduler.init();
    }

    @Override
    public void destroy() throws Exception {
        xxlJobScheduler.destroy();
    }


    // ---------------------- XxlJobScheduler ----------------------

    // conf
    @Value("${datax.job.i18n}")
    private String i18n;

    @Value("${datax.job.accessToken}")
    private String accessToken;

    /**
     * 兼容老部署：仅当显式置为 true 时才允许 accessToken 为空的匿名回调。
     */
    @Value("${datax.job.allowEmptyAccessToken:false}")
    private boolean allowEmptyAccessToken;

    @Value("${spring.mail.username}")
    private String emailUserName;

    @Value("${datax.job.triggerpool.fast.max}")
    private int triggerPoolFastMax;

    @Value("${datax.job.triggerpool.slow.max}")
    private int triggerPoolSlowMax;

    @Value("${datax.job.logretentiondays}")
    private int logretentiondays;

    @Value("${datasource.aes.key}")
    private String dataSourceAESKey;

    /**
     * 社区发行版里的默认密钥，禁止在实际部署中使用。
     */
    private static final String DEFAULT_DATASOURCE_AES_KEY = "AD42F6697B035B75";

    /**
     * bin/db/datax_web.sql 里 admin 账号的初始密码哈希，对应明文 123456（BCrypt.checkpw 实测）。
     */
    private static final String DEFAULT_ADMIN_PASSWORD_HASH = "$2a$10$2KCqRbra0Yn2TwvkZxtfLuWuUP5KyCWsljO/ci5pLD27pqR3TV1vy";

    private void checkDefaultAdminPassword() {
        try {
            JobUser admin = jobUserMapper.loadByUserName("admin");
            if (admin != null && DEFAULT_ADMIN_PASSWORD_HASH.equals(admin.getPassword())) {
                logger.error("account 'admin' still uses the password shipped with bin/db/datax_web.sql, "
                        + "log in and change it before exposing this service.");
            }
        } catch (Exception e) {
            logger.warn("default admin password check skipped: {}", e.getMessage());
        }
    }

    // dao, service

    @Resource
    private JobLogMapper jobLogMapper;
    @Resource
    private JobInfoMapper jobInfoMapper;
    @Resource
    private JobRegistryMapper jobRegistryMapper;
    @Resource
    private JobGroupMapper jobGroupMapper;
    @Resource
    private JobLogReportMapper jobLogReportMapper;
    @Resource
    private JavaMailSender mailSender;
    @Resource
    private DataSource dataSource;
    @Resource
    private JobDatasourceMapper jobDatasourceMapper;
    @Resource
    private JobUserMapper jobUserMapper;
    @Resource
    private TdsqlShardRuleMapper tdsqlShardRuleMapper;

    public String getI18n() {
        return i18n;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public boolean isAllowEmptyAccessToken() {
        return allowEmptyAccessToken;
    }

    public String getEmailUserName() {
        return emailUserName;
    }

    public int getTriggerPoolFastMax() {
        return triggerPoolFastMax < 200 ? 200 : triggerPoolFastMax;
    }

    public int getTriggerPoolSlowMax() {
        return triggerPoolSlowMax < 100 ? 100 : triggerPoolSlowMax;
    }

    public int getLogretentiondays() {
        return logretentiondays < 7 ? -1 : logretentiondays;
    }

    public JobLogMapper getJobLogMapper() {
        return jobLogMapper;
    }

    public JobInfoMapper getJobInfoMapper() {
        return jobInfoMapper;
    }

    public JobRegistryMapper getJobRegistryMapper() {
        return jobRegistryMapper;
    }

    public JobGroupMapper getJobGroupMapper() {
        return jobGroupMapper;
    }

    public TdsqlShardRuleMapper getTdsqlShardRuleMapper() {
        return tdsqlShardRuleMapper;
    }

    public JobLogReportMapper getJobLogReportMapper() {
        return jobLogReportMapper;
    }

    public JavaMailSender getMailSender() {
        return mailSender;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public JobDatasourceMapper getJobDatasourceMapper() {
        return jobDatasourceMapper;
    }

    public String getDataSourceAESKey() {
        return dataSourceAESKey;
    }

    public void setDataSourceAESKey(String dataSourceAESKey) {
        this.dataSourceAESKey = dataSourceAESKey;
    }
}
