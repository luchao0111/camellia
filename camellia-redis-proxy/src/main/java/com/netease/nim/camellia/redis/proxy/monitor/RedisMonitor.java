package com.netease.nim.camellia.redis.proxy.monitor;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.util.ExecutorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 *
 * Created by caojiajun on 2019/11/28.
 */
public class RedisMonitor {

    private static final Logger logger = LoggerFactory.getLogger(RedisMonitor.class);

    private static MonitorCallback monitorCallback;

    private static int intervalSeconds;
    private static ConcurrentHashMap<String, LongAdder> map = new ConcurrentHashMap<>();
    private static Stats stats = new Stats();
    private static final ConcurrentHashMap<String, LongAdder> failCountMap = new ConcurrentHashMap<>();

    private static boolean commandSpendTimeMonitorEnable;
    private static final ConcurrentLinkedQueue<CommandSpendItem> queue = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<String, LongAdder> commandSpendCountMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> commandSpendTotalMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> commandSpendMaxMap = new ConcurrentHashMap<>();

    private static class CommandSpendItem {
        private final String command;
        private final long spendNanoTime;
        public CommandSpendItem(String command, long spendNanoTime) {
            this.command = command;
            this.spendNanoTime = spendNanoTime;
        }
    }

    public static void init(int seconds, boolean commandSpendTimeMonitorEnable, MonitorCallback monitorCallback) {
        intervalSeconds = seconds;
        ExecutorUtils.scheduleAtFixedRate(RedisMonitor::calc, seconds, seconds, TimeUnit.SECONDS);
        RedisMonitor.commandSpendTimeMonitorEnable = commandSpendTimeMonitorEnable;
        if (commandSpendTimeMonitorEnable) {
            new Thread(RedisMonitor::calcCommandSpendTime, "command-spend-time-calc").start();
        }
        RedisMonitor.monitorCallback = monitorCallback;
    }

    public static void incr(Long bid, String bgroup, String command) {
        String key = bid + "|" + bgroup + "|" + command;
        LongAdder count = map.computeIfAbsent(key, k -> new LongAdder());
        count.increment();
    }

    public static void incrFail(String failReason) {
        LongAdder failCount = failCountMap.computeIfAbsent(failReason, k -> new LongAdder());
        failCount.increment();
    }

    public static void incrCommandSpendTime(String command, long spendNanoTime) {
        if (commandSpendTimeMonitorEnable) {
            queue.offer(new CommandSpendItem(command, spendNanoTime));
        }
    }

    private static void calcCommandSpendTime() {
        while (true) {
            try {
                CommandSpendItem item = queue.poll();
                if (item == null) {
                    TimeUnit.MILLISECONDS.sleep(100);
                    continue;
                }
                LongAdder count = commandSpendCountMap.computeIfAbsent(item.command, k -> new LongAdder());
                count.increment();
                LongAdder total = commandSpendTotalMap.computeIfAbsent(item.command, k -> new LongAdder());
                total.add(item.spendNanoTime);
                AtomicLong max = commandSpendMaxMap.computeIfAbsent(item.command, k -> new AtomicLong(0));
                if (item.spendNanoTime > max.get()) {
                    max.set(item.spendNanoTime);
                }
            } catch (Exception e) {
                logger.error("calc command spend time error", e);
            }
        }
    }

    public static Stats getStats() {
        return stats;
    }

    public static JSONObject getStatsJson() {
        JSONObject monitorJson = new JSONObject();
        JSONArray connectJsonArray = new JSONArray();
        JSONObject connectJson = new JSONObject();
        connectJson.put("connect", stats.getClientConnectCount());
        connectJsonArray.add(connectJson);
        monitorJson.put("connectStats", connectJsonArray);

        JSONArray countJsonArray = new JSONArray();
        JSONObject countJson = new JSONObject();
        countJson.put("count", stats.getCount());
        countJson.put("totalReadCount", stats.getTotalReadCount());
        countJson.put("totalWriteCount", stats.getTotalWriteCount());
        countJsonArray.add(countJson);
        monitorJson.put("countStats", countJsonArray);

        JSONArray qpsJsonArray = new JSONArray();
        JSONObject qpsJson = new JSONObject();
        qpsJson.put("qps", stats.getCount() / (stats.getIntervalSeconds() * 1.0));
        qpsJson.put("readQps", stats.getTotalReadCount() / (stats.getIntervalSeconds() * 1.0));
        qpsJson.put("writeQps", stats.getTotalWriteCount() / (stats.getIntervalSeconds() * 1.0));
        qpsJsonArray.add(qpsJson);
        monitorJson.put("qpsStats", qpsJsonArray);

        JSONArray totalJsonArray = new JSONArray();
        for (Stats.TotalStats totalStats : stats.getTotalStatsList()) {
            JSONObject totalJson = new JSONObject();
            totalJson.put("command", totalStats.getCommand());
            totalJson.put("count", totalStats.getCount());
            totalJson.put("qps", totalStats.getCount() / (stats.getIntervalSeconds() * 1.0));
            totalJsonArray.add(totalJson);
        }
        monitorJson.put("total", totalJsonArray);

        JSONArray bigBgroupJsonArray = new JSONArray();
        for (Stats.BidBgroupStats bidBgroupStats : stats.getBidBgroupStatsList()) {
            JSONObject bidBgroupJson = new JSONObject();
            bidBgroupJson.put("bid", bidBgroupStats.getBid() == null ? "default" : String.valueOf(bidBgroupStats.getBid()));
            bidBgroupJson.put("bgroup", bidBgroupStats.getBgroup() == null ? "default" : bidBgroupStats.getBgroup());
            bidBgroupJson.put("count", bidBgroupStats.getCount());
            bidBgroupJson.put("qps", bidBgroupStats.getCount() / (stats.getIntervalSeconds() * 1.0));
            bigBgroupJsonArray.add(bidBgroupJson);
        }
        monitorJson.put("bidbgroup", bigBgroupJsonArray);

        JSONArray detailJsonArray = new JSONArray();
        for (Stats.DetailStats detailStats : stats.getDetailStatsList()) {
            JSONObject detailJson = new JSONObject();
            detailJson.put("bid", detailStats.getBid() == null ? "default" : String.valueOf(detailStats.getBid()));
            detailJson.put("bgroup", detailStats.getBgroup() == null ? "default" : detailStats.getBgroup());
            detailJson.put("command", detailStats.getCommand());
            detailJson.put("count", detailStats.getCount());
            detailJson.put("qps", detailStats.getCount() / (stats.getIntervalSeconds() * 1.0));
            detailJsonArray.add(detailJson);
        }
        monitorJson.put("detail", detailJsonArray);

        JSONArray failJsonArray = new JSONArray();
        for (Map.Entry<String, Long> entry : stats.getFailMap().entrySet()) {
            String failReason = entry.getKey();
            Long count = entry.getValue();
            JSONObject failJson = new JSONObject();
            failJson.put("reason", failReason);
            failJson.put("count", count);
            failJsonArray.add(failJson);
        }
        monitorJson.put("failStats", failJsonArray);

        JSONArray spendJsonArray = new JSONArray();
        for (Stats.SpendStats spendStats : stats.getSpendStatsList()) {
            JSONObject spendJson = new JSONObject();
            spendJson.put("command", spendStats.getCommand());
            spendJson.put("count", spendStats.getCount());
            spendJson.put("avgSpendMs", spendStats.getAvgSpendMs());
            spendJson.put("maxSpendMs", spendStats.getMaxSpendMs());
            spendJsonArray.add(spendJson);
        }
        monitorJson.put("spendStats", spendJsonArray);
        return monitorJson;
    }

    private static void calc() {
        try {
            long totalCount = 0;
            long totalReadCount = 0;
            long totalWriteCount = 0;
            Map<String, Stats.TotalStats> totalStatsMap = new HashMap<>();
            Map<String, Stats.BidBgroupStats> bidBgroupStatsMap = new HashMap<>();
            List<Stats.DetailStats> detailStatsList = new ArrayList<>();

            ConcurrentHashMap<String, LongAdder> map = RedisMonitor.map;
            RedisMonitor.map = new ConcurrentHashMap<>();
            for (Map.Entry<String, LongAdder> entry : map.entrySet()) {
                String[] split = entry.getKey().split("\\|");
                long count = entry.getValue().longValue();
                Long bid = null;
                if (!split[0].equalsIgnoreCase("null")) {
                    bid = Long.parseLong(split[0]);
                }
                String bgroup = null;
                if (!split[1].equalsIgnoreCase("null")) {
                    bgroup = split[1];
                }
                String command = split[2];

                Stats.TotalStats totalStats = totalStatsMap.computeIfAbsent(command, Stats.TotalStats::new);
                totalStats.setCount(totalStats.getCount() + count);

                String key = bid + "|" + bgroup;
                Stats.BidBgroupStats bidBgroupStats = bidBgroupStatsMap.computeIfAbsent(key, k -> new Stats.BidBgroupStats());
                bidBgroupStats.setBid(bid);
                bidBgroupStats.setBgroup(bgroup);
                bidBgroupStats.setCount(bidBgroupStats.getCount() + count);

                detailStatsList.add(new Stats.DetailStats(bid, bgroup, command, count));

                totalCount += count;
                RedisCommand redisCommand = RedisCommand.getRedisCommandByName(command);
                if (redisCommand != null && redisCommand.getType() != null) {
                    if (redisCommand.getType() == RedisCommand.Type.READ) {
                        totalReadCount += count;
                    } else {
                        totalWriteCount += count;
                    }
                }
            }

            List<Stats.SpendStats> spendStatsList = new ArrayList<>();
            for (Map.Entry<String, LongAdder> entry : commandSpendCountMap.entrySet()) {
                String command = entry.getKey();
                long count = entry.getValue().sumThenReset();
                if (count == 0) continue;
                AtomicLong nanoMax = commandSpendMaxMap.get(command);
                double maxSpendMs = 0;
                if (nanoMax != null) {
                    maxSpendMs = nanoMax.getAndSet(0) / 1000000.0;
                }
                double avgSpendMs = 0;
                LongAdder nanoSum = commandSpendTotalMap.get(command);
                if (nanoSum != null) {
                    avgSpendMs = nanoSum.sumThenReset() / (1000000.0 * count);
                }
                Stats.SpendStats spendStats = new Stats.SpendStats();
                spendStats.setCommand(command);
                spendStats.setAvgSpendMs(avgSpendMs);
                spendStats.setMaxSpendMs(maxSpendMs);
                spendStats.setCount(count);
                spendStatsList.add(spendStats);
            }

            Stats stats = new Stats();
            stats.setClientConnectCount(ChannelMonitor.getChannelMap().size());
            stats.setCount(totalCount);
            stats.setTotalReadCount(totalReadCount);
            stats.setTotalWriteCount(totalWriteCount);
            stats.setDetailStatsList(detailStatsList);
            stats.setTotalStatsList(new ArrayList<>(totalStatsMap.values()));
            stats.setBidBgroupStatsList(new ArrayList<>(bidBgroupStatsMap.values()));
            Map<String, Long> failMap = new HashMap<>();
            for (Map.Entry<String, LongAdder> entry : failCountMap.entrySet()) {
                long count = entry.getValue().sumThenReset();
                if (count > 0) {
                    failMap.put(entry.getKey(), count);
                }
            }
            stats.setFailMap(failMap);
            stats.setSpendStatsList(spendStatsList);
            stats.setIntervalSeconds(intervalSeconds);

            RedisMonitor.stats = stats;

            if (monitorCallback != null) {
                monitorCallback.callback(stats);
            }
        } catch (Exception e) {
            logger.error("calc error", e);
        }
    }
}
