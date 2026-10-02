package com.wugui.datax.admin.core.util;

import com.wugui.datax.admin.core.conf.JobAdminConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * i18n util
 *
 * @author xuxueli 2018-01-17 20:39:06
 */
public class I18nUtil {
    private static Logger logger = LoggerFactory.getLogger(I18nUtil.class);

    private static Properties prop = null;
    public static Properties loadI18nProp(){
        if (prop != null) {
            return prop;
        }
        // build i18n prop
        // JobAdminConfig 是 Spring 才初始化的静态单例，非 Spring 环境（单测、启动早期）取它是 NPE，
        // 而本方法是所有失败提示文案的入口 —— 等于"报错比被报的错先炸"。拿不到配置就按默认语言走。
        JobAdminConfig adminConfig = JobAdminConfig.getAdminConfig();
        String i18n = adminConfig == null ? null : adminConfig.getI18n();
        String suffix = (i18n != null && i18n.trim().length() > 0) ? ("_" + i18n.trim()) : "";

        prop = loadPropFile(suffix);
        if (prop == null && !suffix.isEmpty()) {
            // 配置指定的语言文件不存在时退回默认文件，别让文案层整体变 null
            prop = loadPropFile("");
        }
        if (prop == null) {
            logger.error("i18n message{}.properties 加载失败，提示信息将退回空文案。", suffix);
            prop = new Properties();
        }
        return prop;
    }

    private static Properties loadPropFile(String suffix) {
        String i18nFile = MessageFormat.format("i18n/message{0}.properties", suffix);
        try {
            Resource resource = new ClassPathResource(i18nFile);
            EncodedResource encodedResource = new EncodedResource(resource,"UTF-8");
            return PropertiesLoaderUtils.loadProperties(encodedResource);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * get val of i18n key
     *
     * @param key
     * @return
     */
    public static String getString(String key) {
        return loadI18nProp().getProperty(key);
    }

    /**
     * get mult val of i18n mult key, as json
     *
     * @param keys
     * @return
     */
    public static String getMultString(String... keys) {
        Map<String, String> map = new HashMap<String, String>();

        Properties prop = loadI18nProp();
        if (keys!=null && keys.length>0) {
            for (String key: keys) {
                map.put(key, prop.getProperty(key));
            }
        } else {
            for (String key: prop.stringPropertyNames()) {
                map.put(key, prop.getProperty(key));
            }
        }

        String json = JacksonUtil.writeValueAsString(map);
        return json;
    }

}
