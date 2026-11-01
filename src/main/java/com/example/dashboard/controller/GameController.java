package com.example.dashboard.controller;

import com.example.dashboard.model.BoardGame;
import com.example.dashboard.service.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class GameController {

    private final GameService service;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("datasetSize", service.size());
        model.addAttribute("dataPath", service.currentSource());
        return "index";
    }

    @GetMapping("/api/dashboard")
    @ResponseBody
    public Map<String, Object> dashboard(
            @RequestParam(defaultValue = "1990") int yearStart,
            @RequestParam(defaultValue = "2021") int yearEnd,
            @RequestParam(defaultValue = "0") int minUsers,
            @RequestParam(defaultValue = "0.0") double ratingMin,
            @RequestParam(defaultValue = "10.0") double ratingMax,
            @RequestParam(defaultValue = "20") int topN,
            @RequestParam(required = false) String search) {

        List<BoardGame> filtered = service.filter(
                yearStart, yearEnd, minUsers, ratingMin, ratingMax);

        Map<String, Object> result = new HashMap<>();

        // ===== 数据源信息（用于侧栏展示） =====
        result.put("datasetSize", service.size());
        result.put("dataPath", service.currentSource());

        // ===== KPI 指标卡 =====
        result.put("totalGames", filtered.size());
        result.put("avgRating", filtered.stream()
                .collect(Collectors.averagingDouble(BoardGame::getAverage)));
        result.put("maxRating", filtered.stream()
                .mapToDouble(BoardGame::getAverage).max().orElse(0));
        result.put("totalUsers", filtered.stream()
                .mapToLong(BoardGame::getUsersRated).sum());

        // ===== 图1：每年发行数量 =====
        result.put("yearlyCount", service.countByYear(filtered));

        // ===== 图2：每年平均评分 =====
        result.put("yearlyAvg", service.avgByYear(filtered));

        // ===== 图4：评分直方图（后端先分桶，nbins=50）=====
        result.put("histogram", buildHistogram(filtered, 50));

        // ===== 图5：Top N =====
        List<BoardGame> topGames = filtered.stream()
                .sorted(Comparator.comparingDouble(BoardGame::getAverage).reversed())
                .limit(topN)
                .collect(Collectors.toList());
        result.put("topGames", topGames);

        // ===== 图6：散点图原始数据 =====
        result.put("scatter", filtered.stream().map(g -> Map.of(
                "name", g.getName(), "x", g.getUsersRated(),
                "y", g.getAverage(), "year", g.getYear())).toList());

        // ===== 图7：箱线图 =====
        result.put("boxData", service.boxDataByDecade(filtered));

        // ===== 搜索结果 =====
        if (search != null && !search.isBlank()) {
            result.put("searchResults", service.search(search, filtered));
        } else {
            result.put("searchResults", List.of());
        }

        return result;
    }

    @PostMapping("/api/reload")
    @ResponseBody
    public Map<String, Object> reload() {
        try {
            return ok(service.reload());
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    /**
     * 页面上传本地 CSV：文件会落到 {工作目录}/data/boardgame_ratings.csv，
     * 加载成功后当前数据源自动切换为该文件。
     */
    @PostMapping("/api/upload")
    @ResponseBody
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("请选择一个非空的 CSV 文件");
            }
            String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                throw new IllegalArgumentException("只支持 .csv 文件，当前文件：" + filename);
            }
            Path dir = Paths.get(System.getProperty("user.dir"), "data");
            Files.createDirectories(dir);

            // 先落到临时文件校验，通过后再替换正式文件，
            // 避免一次错误上传就把原来的好数据覆盖掉
            Path tmp = Files.createTempFile(dir, "upload-", ".csv");
            file.transferTo(tmp.toFile());

            GameService.LoadResult result;
            try {
                result = service.loadFrom(tmp.toString());
            } catch (RuntimeException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }

            Path target = dir.resolve("boardgame_ratings.csv").toAbsolutePath();
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            service.retarget(target.toString());

            return ok(new GameService.LoadResult(target.toString(),
                    result.totalRows(), result.loaded(),
                    result.skippedDirty(), result.skippedInvalid()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    private Map<String, Object> ok(GameService.LoadResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("source", r.source());
        m.put("totalRows", r.totalRows());
        m.put("loaded", r.loaded());
        m.put("skippedDirty", r.skippedDirty());
        m.put("skippedInvalid", r.skippedInvalid());
        return m;
    }

    private Map<String, Object> fail(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return m;
    }

    private List<Map<String, Object>> buildHistogram(List<BoardGame> games, int bins) {
        if (games.isEmpty()) return List.of();
        DoubleSummaryStatistics stat = games.stream()
                .mapToDouble(BoardGame::getAverage).summaryStatistics();
        double min = stat.getMin(), max = stat.getMax();
        // lambda 捕获的变量必须是 effectively final，这里用独立变量承接
        double bucketWidth = (max - min) / bins;
        if (bucketWidth == 0) bucketWidth = 0.1;
        final double width = bucketWidth;

        Map<Integer, Long> buckets = games.stream().collect(
                Collectors.groupingBy(
                        // 取到最大值时会算出 index == bins，需要夹到最后一桶，否则最大值会被丢掉
                        g -> Math.min(bins - 1, (int) ((g.getAverage() - min) / width)),
                        Collectors.counting()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < bins; i++) {
            double start = min + i * width;
            result.add(Map.of(
                    "bin", String.format(Locale.ROOT, "%.2f", start),
                    "count", buckets.getOrDefault(i, 0L)));
        }
        return result;
    }
}
