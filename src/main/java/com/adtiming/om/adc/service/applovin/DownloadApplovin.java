// Copyright 2020 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.adtiming.om.adc.service.applovin;

import com.adtiming.om.adc.dto.ReportAdnData;
import com.adtiming.om.adc.dto.ReportTask;
import com.adtiming.om.adc.service.AdnBaseService;
import com.adtiming.om.adc.service.AppConfig;
import com.adtiming.om.adc.util.MapHelper;
import com.adtiming.om.adc.util.MyHttpClient;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.adtiming.om.adc.util.MapHelper.getInt;

@Service
public class DownloadApplovin extends AdnBaseService {

    private static final Logger LOG = LogManager.getLogger();

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private AppConfig cfg;

    @Override
    public void setAdnInfo() {
        this.adnId = 8;
        this.adnName = "Adcolony";
    }

    @Override
    public void executeTaskImpl(ReportTask o) {
        try {
            executeApplovinTask(o);
        } catch (Exception e) {
            LOG.error("[{}] executeTaskImpl error, taskId:{}", this.adnName, o.id, e);
        }
    }

    private void executeApplovinTask(ReportTask task) {
        String appId = task.adnAppId;
        String apiKey = task.adnApiKey;
        String day = task.day;
        // Set up AdSense Management API client.
        if (StringUtils.isBlank(appId)) {
            LOG.error("Applovin，appKey is null");
            return;
        }


        LOG.info("[Applovin] executeTaskImpl start, appId:{}, apiKey:{}, day:{}", appId, apiKey, day);
        long start = System.currentTimeMillis();
        updateTaskStatus(jdbcTemplate, task.id, 1, "");
        StringBuilder err = new StringBuilder();
        String error;
        String json_data = downJsonData(task.id, appId, apiKey, day, err);
        if (StringUtils.isNotBlank(json_data) && err.length() == 0) {
            error = jsonDataImportDatabase(json_data, day, appId, apiKey);
            if (StringUtils.isBlank(error)) {
                error = savePrepareReportData(task, day, appId);
                if (StringUtils.isBlank(error))
                    error = reportLinkedToStat(task, appId);
            }
        } else {
            error = err.toString();
        }
        int status = StringUtils.isBlank(error) || "data is null".equals(error) ? 2 : 3;
        if (task.runCount > 5 && status != 2) {
            LOG.error("[Applovin] executeTaskImpl error,run count:{},taskId:{},msg:{}", task.runCount + 1, task.id, error);
        }
        updateTaskStatus(jdbcTemplate, task.id, status, error);

        LOG.info("[Applovin] executeTaskImpl end, appId:{}, apiKey:{}, day:{}, cost:{}", appId, apiKey, day, System.currentTimeMillis() - start);
    }

    private String downJsonData(int taskId, String appId, String apiKey, String day, StringBuilder err) {
        String url = "https://r.applovin.com/report?api_key=" + apiKey + "&columns=day%2Chour%2Cimpressions%2Cclicks%2Cctr%2Crevenue" +
                "%2Cecpm%2Ccountry%2Cad_type%2Csize%2Cdevice_type%2Cplatform%2Capplication%2Cpackage_name%2Cplacement" +
                "%2Capplication_is_hidden%2Czone%2Czone_id" +
                "&format=json&start=" + day + "&end=" + day;
        String json_data = "";
        HttpEntity entity = null;
        LOG.info("[Applovin] downJsonData start, taskId:{}, appId:{}, apiKey:{}, day:{}", taskId, appId, apiKey, day);
        LOG.info("[Applovin] request url:{}", url);
        long start = System.currentTimeMillis();
        try {
            updateReqUrl(jdbcTemplate, taskId, url);
            LOG.info("Applovin:" + LocalDateTime.now().toLocalTime());
            HttpGet httpGet = new HttpGet(url);
            RequestConfig requestConfig = RequestConfig.custom().setSocketTimeout(5 * 60 * 1000).setProxy(cfg.httpProxy).build();//设置请求和传输超时时间
            httpGet.setConfig(requestConfig);
            HttpResponse response = MyHttpClient.getInstance().execute(httpGet);
            StatusLine sl = response.getStatusLine();
            if (sl.getStatusCode() != 200) {
                err.append(String.format("request report response statusCode:%d", sl.getStatusCode()));
                return json_data;
            }

            entity = response.getEntity();
            if (entity == null) {
                err.append("request report response enity is null");
                return json_data;
            }
            json_data = EntityUtils.toString(entity);
        } catch (Exception ex) {
            err.append(String.format("downJsonData error,msg:%s", ex.getMessage()));
        } finally {
            EntityUtils.consumeQuietly(entity);
        }
        LOG.info("[Applovin] downJsonData end, taskId:{}, appId:{}, apiKey:{}, day:{}, cost:{}", taskId, appId, apiKey, day, System.currentTimeMillis() - start);
        return json_data;
    }

    private String jsonDataImportDatabase(String jsonData, String day, String appId, String apiKey) {
        String sql_delete = "DELETE FROM report_applovin WHERE day=? AND sdkKey=?";
        try {
            jdbcTemplate.update(sql_delete, day, appId);
        } catch (Exception e) {
            return String.format("delete report_applovin error,msg:%s", e.getMessage());
        }
        LOG.info("[Applovin] jsonDataImportDatabase start, appId:{}, apiKey:{}, day:{}", appId, apiKey, day);
        long start = System.currentTimeMillis();
        String error = "";
        String sql_insert = "INSERT INTO report_applovin (day,hour,country,platform,application,package_name,placement," +
                "ad_type,device_type,application_is_hidden,zone,zone_id,size,impressions,clicks,ctr,revenue,ecpm,sdkKey)  " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try {
            int count = 0;
            List<Object[]> lsParm = new ArrayList<>();
            JSONObject jobj = JSONObject.parseObject(jsonData);
            if (StringUtils.isBlank(jobj.getString("results")))
                return "response results is null";

            JSONArray jsonArray = JSONArray.parseArray(jobj.getString("results"));
            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                count++;
                Object[] params = new Object[]{obj.get("day"), obj.get("hour"), obj.get("country"), obj.get("platform"), obj.get("application"),
                        obj.get("package_name"), obj.get("placement"), obj.get("ad_type"), obj.get("device_type"), obj.get("application_is_hidden"),
                        obj.get("zone"), obj.get("zone_id"), obj.get("size"), obj.get("impressions"), obj.get("clicks"),
                        obj.get("ctr") == null ? 0 : obj.get("ctr"), obj.get("revenue") == null ? 0 : obj.get("revenue"),
                        obj.get("ecpm") == null ? 0 : obj.get("ecpm"), appId};
                if (count > 1000) {
                    jdbcTemplate.batchUpdate(sql_insert, lsParm);
                    count = 1;
                    lsParm = new ArrayList<>();
                }
                lsParm.add(params);
            }
            if (!lsParm.isEmpty()) {
                jdbcTemplate.batchUpdate(sql_insert, lsParm);
            }
        } catch (Exception e) {
            error = String.format("insert report_applovin error, msg:%s", e.getMessage());
        }
        LOG.info("[Applovin] jsonDataImportDatabase end, appId:{}, apiKey:{}, day:{}, cost:{}", appId, apiKey, day, System.currentTimeMillis() - start);
        return error;
    }

    private String savePrepareReportData(ReportTask task, String reportDay, String appId) {
        LOG.info("[Applovin] savePrepareReportData start, taskId:{}", task.id);
        long start = System.currentTimeMillis();
        String error;
        try {
            String whereSql = String.format("b.adn_app_key='%s'", appId);
            List<Map<String, Object>> instanceInfoList = getInstanceList(whereSql);

            Map<String, Map<String, Object>> placements = instanceInfoList.stream().collect(Collectors.toMap(m ->
                    MapHelper.getString(m, "placement_key"), m -> m, (existingValue, newValue) -> existingValue));
            placements.putAll(instanceInfoList.stream().collect(Collectors.toMap(m ->
                    MapHelper.getString(m, "app_id"), m -> m, (existingValue, newValue) -> existingValue)));

            // instance's placement_key changed
            Set<Integer> insIds = instanceInfoList.stream().map(o-> getInt(o, "instance_id")).collect(Collectors.toSet());
            List<Map<String, Object>> oldInstanceList = getOldInstanceList(insIds);
            if (!oldInstanceList.isEmpty()) {
                placements.putAll(oldInstanceList.stream().collect(Collectors.toMap(m ->
                        MapHelper.getString(m, "placement_key"), m -> m, (existingValue, newValue) -> existingValue)));
                placements.putAll(oldInstanceList.stream().collect(Collectors.toMap(m ->
                        MapHelper.getString(m, "app_id"), m -> m, (existingValue, newValue) -> existingValue)));
            }

            String dataSql = "select day,left(hour,2) hour,country,platform," +
                    "case when zone_id is null or zone_id='' then package_name else  zone_id end data_key," +
                    "sum(impressions) AS api_impr,sum(clicks) AS api_click,sum(revenue) AS revenue" +
                    " from report_applovin where day=? and sdkKey=? " +
                    " group by hour,day,country,package_name,zone_id ";

            List<ReportAdnData> oriDataList = jdbcTemplate.query(dataSql, ReportAdnData.ROWMAPPER, reportDay, appId);

            if (oriDataList.isEmpty())
                return "data is empty";

            error = toAdnetworkLinked(task, appId, placements, oriDataList);

        } catch (Exception e) {
            error = String.format("savePrepareReportData error, msg:%s", e.getMessage());
        }
        LOG.info("[Applovin] savePrepareReportData end, taskId:{}, cost:{}", task.id, System.currentTimeMillis() - start);
        return error;
    }
}
