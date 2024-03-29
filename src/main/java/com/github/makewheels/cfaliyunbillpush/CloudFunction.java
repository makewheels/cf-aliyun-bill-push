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
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.bssopenapi.model.v20171214.QueryAccountBalanceRequest;
import com.aliyuncs.bssopenapi.model.v20171214.QueryAccountBalanceResponse;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

public class CloudFunction implements StreamRequestHandler {
    private Client client;

    public Client getClient() {
        if (client != null) {
            return client;
        }
        Config config = new Config()
                .setAccessKeyId(System.getenv("bill_accessKeyId"))
                .setAccessKeySecret(System.getenv("bill_accessKeySecret"));
//                .setAccessKeyId("")
//                .setAccessKeySecret("");
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

    /**
     * 组装html需要的，可视化的，给人看的金额
     *
     * @param cost
     * @return
     */
    private String convertMapToHtml(Map<String, Integer> cost) {
        StringBuilder stringBuilder = new StringBuilder();
        //排序
        Map<String, Integer> linkedHashMap = new LinkedHashMap<>();
        cost.entrySet().stream()
                .sorted((o1, o2) -> o2.getValue() - o1.getValue())
                .forEach(e -> linkedHashMap.put(e.getKey(), e.getValue()));
        List<String> keys = new ArrayList<>(linkedHashMap.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            Integer integer = linkedHashMap.get(key);
            stringBuilder.append("<font color=\"#0000FF\"><b>" + key + "</b></font>:&nbsp;"
                    + integer / 100.0);
            if (i != keys.size() - 1) {
                stringBuilder.append(",&nbsp;");
            }
        }
        return stringBuilder.toString();
    }

    /**
     * 获取账户余额
     */
    public int getBalance() {
        DefaultProfile profile = DefaultProfile.getProfile("cn-beijing",
                System.getenv("bill_accessKeyId"), System.getenv("bill_accessKeySecret"));
        IAcsClient client = new DefaultAcsClient(profile);

        QueryAccountBalanceRequest request = new QueryAccountBalanceRequest();
        try {
            QueryAccountBalanceResponse response = client.getAcsResponse(request);
            return (int) (Double.parseDouble(response.getData().getAvailableAmount()) * 100);
        } catch (ClientException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 发邮件
     */
    private void sendEmail(
            int balance, List<DailyCost> allDayCosts,
            Map<String, Integer> week, Map<String, Integer> month) {
        //组装发送邮件参数
        double balanceInDouble = balance / 100.0;
        JSONObject body = new JSONObject();
        body.put("toAddress", "finalbird@foxmail.com");
        body.put("fromAlias", "推送中心");
        body.put("subject", "日报：阿里云 " + balanceInDouble);
        body.put("htmlBody", "yesterday:&nbsp;" + convertMapToHtml(allDayCosts.get(0).getMap()) + "<br>"
                + "week:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;" + convertMapToHtml(week) + "<br>"
                + "month:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;" + convertMapToHtml(month) + "<br>"
                + "balance:&nbsp;" + balanceInDouble
        );
        //调用推送中心
        String response = HttpUtil.post(
                "http://micorservice.pushcenter.cc:5025/push/sendEmail",
                body.toJSONString());
        System.out.println("发邮件结果：" + response);
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

        System.out.println("yesterday = " + allDayCosts.get(0).getMap());
        System.out.println("week = " + week);
        System.out.println("month = " + month);
        int balance = getBalance();
        System.out.println("balance = " + balance);
        sendEmail(balance, allDayCosts, week, month);
    }

    public static void main(String[] args) {
        new CloudFunction().handleRequest(null, null, null);
    }
}
