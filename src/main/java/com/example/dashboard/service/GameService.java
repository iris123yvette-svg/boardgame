package com.example.dashboard.service;

import com.example.dashboard.model.BoardGame;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private static final int MIN_YEAR = 1950;
    private static final int MAX_YEAR = 2021;
    private static final List<String> REQUIRED_COLUMNS = List.of(
            "name", "year", "rank", "average", "bayes_average", "users_rated");

    private final ResourceLoader resourceLoader;

    private volatile String currentSource;

    private volatile List<BoardGame> allGames = List.of();

    public GameService(ResourceLoader resourceLoader,
                       @Value("${boardgame.data-path:classpath:boardgame_ratings.csv}") String dataPath) {
        this.resourceLoader = resourceLoader;
        this.currentSource = normalize(dataPath);
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /* ==================== 数据加载 ==================== */

    /** 加载结果，用于回显给前端 */
    public record LoadResult(String source, int totalRows, int loaded,
                             int skippedDirty, int skippedInvalid) {
    }

    /** 重新加载当前数据源（改完本地文件后调用，无需重启应用） */
    public synchronized LoadResult reload() {
        return loadFrom(currentSource);
    }

    /** 从指定数据源加载：支持 classpath:xxx、file:D:/xxx.csv、裸路径 D:/xxx.csv */
    public synchronized LoadResult loadFrom(String source) {
        String src = normalize(source);
        Resource resource = resourceLoader.getResource(src);
        if (!resource.exists()) {
            throw new IllegalStateException("数据文件不存在：" + src);
        }

        List<BoardGame> parsed = new ArrayList<>();
        int total = 0, dirty = 0, invalid = 0;

        try (Reader raw = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(raw)) {

            // 跳过 Excel 另存为 UTF-8 CSV 时常见的 BOM，否则表头会变成 "\uFEFFname"
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }

            CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreSurroundingSpaces(true)
                    .setTrim(true)
                    .build()
                    .parse(reader);

            checkHeader(parser.getHeaderMap());

            for (CSVRecord r : parser) {
                total++;
                try {
                    BoardGame g = new BoardGame();
                    g.setName(r.get("name").trim());
                    g.setYear(Integer.parseInt(r.get("year").trim()));
                    g.setRank(Integer.parseInt(r.get("rank").trim()));
                    g.setAverage(Double.parseDouble(r.get("average").trim()));
                    g.setBayesAverage(Double.parseDouble(r.get("bayes_average").trim()));
                    g.setUsersRated(Integer.parseInt(r.get("users_rated").trim()));
                    // 数据清洗：保留 1950-2021，去掉 year=3500 等脏数据
                    if (g.getYear() < MIN_YEAR || g.getYear() > MAX_YEAR) {
                        dirty++;
                        continue;
                    }
                    parsed.add(g);
                } catch (RuntimeException ex) {
                    invalid++;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取数据文件失败：" + src + "（" + e.getMessage() + "）", e);
        }

        if (parsed.isEmpty()) {
            throw new IllegalStateException("没有解析到有效数据，请确认列名为 "
                    + String.join(",", REQUIRED_COLUMNS) + " 且文件编码为 UTF-8");
        }

        this.allGames = List.copyOf(parsed);
        this.currentSource = src;
        log.info("数据源 {} 加载完成：总行 {}, 有效 {}, 年份越界丢弃 {}, 格式错误跳过 {}",
                src, total, parsed.size(), dirty, invalid);
        return new LoadResult(src, total, parsed.size(), dirty, invalid);
    }

    private void checkHeader(Map<String, Integer> header) {
        if (header == null || header.isEmpty()) {
            throw new IllegalStateException("CSV 文件是空的或没有表头");
        }
        List<String> missing = REQUIRED_COLUMNS.stream()
                .filter(c -> !header.containsKey(c))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("CSV 缺少列：" + String.join(",", missing)
                    + "（当前表头：" + String.join(",", header.keySet()) + "）");
        }
    }

    private String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "classpath:boardgame_ratings.csv";
        }
        String p = path.trim();
        if (p.startsWith("classpath:") || p.startsWith("file:")) {
            return p;
        }
        return "file:" + p;
    }

    /* ==================== 对外查询 ==================== */

    public String currentSource() {
        return currentSource;
    }

    public synchronized void retarget(String source) {
        this.currentSource = normalize(source);
    }

    public int size() {
        return allGames.size();
    }

    public List<BoardGame> all() {
        return allGames;
    }

    public List<BoardGame> filter(Integer yearStart, Integer yearEnd,
                                  Integer minUsers, Double ratingMin, Double ratingMax) {
        return allGames.stream()
                .filter(g -> yearStart == null || g.getYear() >= yearStart)
                .filter(g -> yearEnd == null || g.getYear() <= yearEnd)
                .filter(g -> minUsers == null || g.getUsersRated() >= minUsers)
                .filter(g -> ratingMin == null || g.getAverage() >= ratingMin)
                .filter(g -> ratingMax == null || g.getAverage() <= ratingMax)
                .collect(Collectors.toList());
    }

    /** 名称搜索 */
    public List<BoardGame> search(String keyword, List<BoardGame> pool) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String kw = keyword.toLowerCase(Locale.ROOT);
        return pool.stream()
                .filter(g -> g.getName().toLowerCase(Locale.ROOT).contains(kw))
                .limit(20)
                .collect(Collectors.toList());
    }

    /** 对应图1：每年发行数量 */
    public Map<Integer, Long> countByYear(List<BoardGame> games) {
        return games.stream().collect(
                Collectors.groupingBy(BoardGame::getYear, TreeMap::new, Collectors.counting()));
    }

    /** 对应图2：每年平均评分 */
    public Map<Integer, Double> avgByYear(List<BoardGame> games) {
        return games.stream().collect(
                Collectors.groupingBy(BoardGame::getYear, TreeMap::new,
                        Collectors.averagingDouble(BoardGame::getAverage)));
    }

    /** 对应图7：年代分组箱线图数据（返回原始数据，前端 ECharts 算分位） */
    public Map<String, List<Double>> boxDataByDecade(List<BoardGame> games) {
        return games.stream().collect(Collectors.groupingBy(
                BoardGame::getDecadeLabel,
                TreeMap::new,
                Collectors.mapping(BoardGame::getAverage, Collectors.toList())));
    }
}
