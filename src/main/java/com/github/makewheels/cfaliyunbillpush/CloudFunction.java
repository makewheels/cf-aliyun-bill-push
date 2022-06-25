package com.github.makewheels.cfaliyunbillpush;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aliyun.bssopenapi20171214.Client;
import com.aliyun.bssopenapi20171214.models.QueryAccountTransactionsRequest;
import com.aliyun.bssopenapi20171214.models.QueryAccountTransactionsResponseBody;
import com.aliyun.fc.runtime.Context;
import com.aliyun.fc.runtime.StreamRequestHandler;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class CloudFunction implements StreamRequestHandler {
    private Client client;

    public Client getClient() {
        if (client != null) {
            return client;
        }
        Config config = new Config()
                .setAccessKeyId(System.getenv("bill_accessKeyId"))
                .setAccessKeySecret(System.getenv("bill_accessKeySecret"));
        config.endpoint = "business.aliyuncs.com";
        try {
            client = new Client(config);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return client;
    }

    /**
     * 发一次阿里云的请求
     */
    private QueryAccountTransactionsResponseBody request(
            String createTimeStart, String createTimeEnd, int pageNum) throws Exception {
        //发起请求
        QueryAccountTransactionsRequest request = new QueryAccountTransactionsRequest()
                .setPageNum(pageNum)
                .setPageSize(50)
                .setTransactionType("Consumption")
                .setCreateTimeStart(createTimeStart)
                .setCreateTimeEnd(createTimeEnd);
        return getClient().queryAccountTransactionsWithOptions(request, new RuntimeOptions()).getBody();
    }

    /**
     * 获取一天的，每个产品的，金额
     */
    private Map<String, Integer> getOneDay(long timestamp) throws Exception {
        //这一天的零点，到下一天的零点
        String createTimeStart = DateUtil.formatDate(new Date(timestamp)) + "T00:00:00Z";
        String createTimeEnd = DateUtil.formatDate(new Date(timestamp + 86400000)) + "T00:00:00Z";

        //key: ProductCode, value: 多少钱，单位分
        Map<String, Integer> map = new HashMap<>();

        //一直分页查询
        int pageNum = 1;
        QueryAccountTransactionsResponseBody.QueryAccountTransactionsResponseBodyData data;
        do {
            QueryAccountTransactionsResponseBody response = request(createTimeStart, createTimeEnd, pageNum);
            data = response.getData();
            System.out.print(response.getRequestId() + " " + response.getData().getTotalCount() + " ");
            List<QueryAccountTransactionsResponseBody.
                    QueryAccountTransactionsResponseBodyDataAccountTransactionsListAccountTransactionsList>
                    transactions = data.getAccountTransactionsList().getAccountTransactionsList();
            //遍历解析结果到map
            for (QueryAccountTransactionsResponseBody
                    .QueryAccountTransactionsResponseBodyDataAccountTransactionsListAccountTransactionsList
                    transaction : transactions) {
                //金额都按照分计算，只有integer，没有double
                int amount = (int) (Double.parseDouble(transaction.getAmount()) * 100);
                map.merge(transaction.getRemarks(), amount, Integer::sum);
            }
            pageNum++;
        } while (data.getPageNum() * data.getPageSize() < data.getTotalCount());
        return map;
    }

    /**
     * 拿到所有天的，支持账单
     */
    private List<DailyCost> getAllDayCosts() {
        List<DailyCost> dailyCosts = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            LocalDateTime localDateTime = LocalDateTime.now().plusDays(-i);
            long timestamp = localDateTime.toInstant(ZoneOffset.ofHours(8)).toEpochMilli();
            DailyCost dailyCost = new DailyCost();
            dailyCost.setYear(localDateTime.getYear());
            dailyCost.setMonth(localDateTime.getMonthValue());
            dailyCost.setDay(localDateTime.getDayOfMonth());
            try {
                dailyCost.setMap(getOneDay(timestamp));
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println(JSON.toJSONString(dailyCost));
            dailyCosts.add(dailyCost);
        }
        return dailyCosts;
    }

    /**
     * 合并两个map
     */
    private void mergeMaps(Map<String, Integer> from, Map<String, Integer> to) {
        Set<String> keySet = from.keySet();
        for (String key : keySet) {
            to.merge(key, from.get(key), Integer::sum);
        }
    }

    private void sendEmail(List<DailyCost> allDayCosts, Map<String, Integer> week, Map<String, Integer> month) {
        //组装发送邮件参数
        JSONObject body = new JSONObject();
        body.put("toAddress", "finalbird@foxmail.com");
        body.put("fromAlias", "push-center");
        body.put("subject", "阿里云消费");
        body.put("htmlBody", "昨天：" + allDayCosts.get(0).getMap()
                + JSON.toJSONString(week) + "<br>"
                + JSON.toJSONString(month));
        //调用推送中心
        String response = HttpUtil.post(
                "http://push-center.java8.icu:5025/push/sendEmail",
                body.toJSONString());
        System.out.println(response);
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) {
        List<DailyCost> allDayCosts = getAllDayCosts();
        Map<String, Integer> week = new HashMap<>();
        Map<String, Integer> month = new HashMap<>();
        for (int i = 0; i < 7; i++) {
            mergeMaps(allDayCosts.get(i).getMap(), week);
        }
        for (int i = 0; i < 30; i++) {
            mergeMaps(allDayCosts.get(i).getMap(), month);
        }

        System.out.println(allDayCosts.get(0).getMap());
        System.out.println(week);
        System.out.println(month);
    }

    public static void main(String[] args) {
        new CloudFunction().handleRequest(null, null, null);
    }
}
