package com.wugui.datax.executor.service.command;

import com.wugui.datatx.core.biz.model.TriggerParam;
import com.wugui.datatx.core.enums.IncrementTypeEnum;
import com.wugui.datatx.core.log.JobLogger;
import com.wugui.datatx.core.util.Constants;
import com.wugui.datatx.core.util.DateUtil;
import com.wugui.datatx.core.util.JobParamSafety;
import com.wugui.datax.executor.util.SystemUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static com.wugui.datatx.core.util.Constants.SPLIT_COMMA;
import static com.wugui.datax.executor.service.jobhandler.DataXConstant.*;

/**
 * DataX command build
 *
 * @author jingwk 2020-06-07
 */
public class BuildCommand {

    /**
     * DataX command build
     * @param tgParam
     * @param tmpFilePath
     * @param dataXPyPath
     * @param pythonPath python 解释器，为空时退回历史上的写死值 "python"
     * @return
     */
    public static String[] buildDataXExecutorCmd(TriggerParam tgParam, String tmpFilePath, String dataXPyPath, String pythonPath) {
        // 挨着 shell 的最后一道判定，和管理端 add/update 用的是同一份实现（datax-core）。
        // 为什么执行器还要判一次：库里可能有历史行、批量建任务会把模板的 jvmParam 原样拷进来，
        // 只在写入时挡不等于跑的时候安全 —— 而 datax.py 收尾是 Popen(cmd, shell=True)。
        String paramDeny = JobParamSafety.denyMessage(tgParam);
        if (paramDeny != null) {
            throw new IllegalStateException("拒绝执行该作业：" + paramDeny);
        }

        // command process
        //"--loglevel=debug"
        List<String> cmdArr = new ArrayList<>();
        cmdArr.add(StringUtils.isBlank(pythonPath) ? "python" : pythonPath.trim());
        String dataXHomePath = SystemUtils.getDataXHomePath();
        if (StringUtils.isNotEmpty(dataXHomePath)) {
            dataXPyPath = dataXHomePath.contains("bin") ? dataXHomePath + DEFAULT_DATAX_PY : dataXHomePath + "bin" + File.separator + DEFAULT_DATAX_PY;
        }
        if (StringUtils.isBlank(dataXPyPath)) {
            throw new IllegalStateException("未找到 datax.py：请配置 DATAX_HOME 环境变量，或修正 datax-executor 配置项 datax.pypath");
        }
        if (!new File(dataXPyPath).isFile()) {
            throw new IllegalStateException("datax.py 文件不存在：" + dataXPyPath);
        }
        cmdArr.add(dataXPyPath);
        String doc = buildDataXParam(tgParam);
        if (StringUtils.isNotBlank(doc)) {
            cmdArr.add(doc.replaceAll(SPLIT_SPACE, TRANSFORM_SPLIT_SPACE));
        }
        cmdArr.add(tmpFilePath);
        return cmdArr.toArray(new String[cmdArr.size()]);
    }

    private static String buildDataXParam(TriggerParam tgParam) {
        StringBuilder doc = new StringBuilder();
        String jvmParam = StringUtils.isNotBlank(tgParam.getJvmParam()) ? tgParam.getJvmParam().trim() : tgParam.getJvmParam();
        String partitionStr = tgParam.getPartitionInfo();
        if (StringUtils.isNotBlank(jvmParam)) {
            doc.append(JVM_CM).append(TRANSFORM_QUOTES).append(jvmParam).append(TRANSFORM_QUOTES);
        }

        Integer incrementType = tgParam.getIncrementType();
        String replaceParam = StringUtils.isNotBlank(tgParam.getReplaceParam()) ? tgParam.getReplaceParam().trim() : null;

        if (incrementType != null && replaceParam != null) {

            if (IncrementTypeEnum.TIME.getCode() == incrementType) {
                if (doc.length() > 0) doc.append(SPLIT_SPACE);
                // 跟着管理端的判定一起 trim：库里历史行可能是 " Timestamp " 或 " yyyy-MM-dd "，
                // 原先直接拿原始串 equals("Timestamp")，这类值会被当成日期样式，
                // 于是 sdf.format() 产出带空格的两端又被替换成 %，拼进 -p"..." 后 WHERE 条件是坏的。
                String replaceParamType = StringUtils.trimToNull(tgParam.getReplaceParamType());

                if (StringUtils.isBlank(replaceParamType) || replaceParamType.equals("Timestamp")) {
                    long startTime = tgParam.getStartTime().getTime() / 1000;
                    long endTime = tgParam.getTriggerTime().getTime() / 1000;
                    doc.append(PARAMS_CM).append(TRANSFORM_QUOTES).append(String.format(replaceParam, startTime, endTime));
                } else {
                    SimpleDateFormat sdf = new SimpleDateFormat(replaceParamType);
                    String endTime = sdf.format(tgParam.getTriggerTime()).replaceAll(SPLIT_SPACE, PERCENT);
                    String startTime = sdf.format(tgParam.getStartTime()).replaceAll(SPLIT_SPACE, PERCENT);
                    doc.append(PARAMS_CM).append(TRANSFORM_QUOTES).append(String.format(replaceParam, startTime, endTime));
                }
                //buildPartitionCM(doc, partitionStr);
                doc.append(TRANSFORM_QUOTES);

            } else if (IncrementTypeEnum.ID.getCode() == incrementType) {
                long startId = tgParam.getStartId();
                long endId = tgParam.getEndId();
                if (doc.length() > 0) doc.append(SPLIT_SPACE);
                doc.append(PARAMS_CM).append(TRANSFORM_QUOTES).append(String.format(replaceParam, startId, endId));
                doc.append(TRANSFORM_QUOTES);
            }
        }

        if (incrementType != null && IncrementTypeEnum.PARTITION.getCode() == incrementType) {
            if (StringUtils.isNotBlank(partitionStr)) {
                List<String> partitionInfo = Arrays.asList(partitionStr.split(SPLIT_COMMA));
                if (doc.length() > 0) doc.append(SPLIT_SPACE);
                doc.append(PARAMS_CM).append(TRANSFORM_QUOTES).append(String.format(PARAMS_CM_V_PT, buildPartition(partitionInfo))).append(TRANSFORM_QUOTES);
            }
        }

        JobLogger.log("------------------Command parameters:" + doc);
        return doc.toString();
    }


    private void buildPartitionCM(StringBuilder doc, String partitionStr) {
        if (StringUtils.isNotBlank(partitionStr)) {
            doc.append(SPLIT_SPACE);
            List<String> partitionInfo = Arrays.asList(partitionStr.split(SPLIT_COMMA));
            doc.append(String.format(PARAMS_CM_V_PT, buildPartition(partitionInfo)));
        }
    }

    private static String buildPartition(List<String> partitionInfo) {
        // 三段都 trim：与 datax-core 的 JobParamSafety 口径一致（那边判的也是 trim 后的片段）。
        // 不 trim 的话 "ds , 0 , yyyy-MM-dd" 这种手写带空格的合法配置会在此处抛
        // NumberFormatException，任务日志只有一行堆栈，而管理端却告诉用户这是合法输入。
        String field = partitionInfo.get(0).trim();
        int timeOffset = Integer.parseInt(partitionInfo.get(1).trim());
        String timeFormat = partitionInfo.get(2).trim();
        String partitionTime = DateUtil.format(DateUtil.addDays(new Date(), timeOffset), timeFormat);
        return field + Constants.EQUAL + partitionTime;
    }

}
