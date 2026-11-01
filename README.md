# 桌游数据可视化看板 · BoardGame Dashboard

基于 **Spring Boot 3 + Thymeleaf + ECharts 5** 的桌游（BoardGameGeek）数据可视化看板。
后端一次性聚合筛选结果，前端单页渲染 KPI 卡 + 6 张图表 + 搜索结果表，筛选即联动刷新。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.2.0、Java 17、commons-csv |
| 模板 | Thymeleaf |
| 前端 | 原生 JS + ECharts 5.5（本地引入，不依赖 CDN） |
| 数据 | CSV 文件（BGG 导出格式） |

## 快速开始

前置：JDK 17+、Maven（IDEA 自带 Maven 亦可）。

```bash
git clone https://github.com/iris123yvette-svg/boardgame.git
cd boardgame
mvn spring-boot:run
```

打包运行：

```bash
mvn clean package -DskipTests
java -jar target/boardgame-dashboard-1.0.0.jar
```

Windows 下可直接双击 `run.bat`（自动用 JDK 17 启动并打开页面）。

浏览器访问 <http://localhost:8080> 。

## 数据文件

默认读取 `src/main/resources/boardgame_ratings.csv`，列名固定为：

```
name, year, rank, average, bayes_average, users_rated
```

多余列会被忽略，顺序无所谓，编码建议 UTF-8（带 BOM 也能读）。

导入自己的数据有两种方式：

1. **改配置** —— `src/main/resources/application.yml`：

   ```yaml
   boardgame:
     data-path: "D:/data/bgg_ratings.csv"   # 也可写 classpath:xxx 或相对路径
   ```

2. **页面上传** —— 左侧「数据源」卡片点「上传 CSV」，文件存到项目下的 `data/` 目录并立即生效；改完磁盘上的文件点「重新加载」即可，无需重启。

数据清洗规则：只保留 `year` 在 **1950–2021** 之间的记录；单行数字格式异常会被跳过，不中断整体导入。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 看板页面 |
| GET | `/api/dashboard` | 按筛选条件返回全部图表数据（JSON） |
| POST | `/api/reload` | 重新加载当前数据源 |
| POST | `/api/upload` | 上传 CSV（先校验后替换，失败不影响已有数据） |

`/api/dashboard` 查询参数（均有默认值）：`yearStart`、`yearEnd`、`minUsers`、`ratingMin`、`ratingMax`、`topN`、`search`。

返回字段：`totalGames`、`avgRating`、`maxRating`、`totalUsers`、`yearlyCount`、`yearlyAvg`、`histogram`、`topGames`、`scatter`、`boxData`、`searchResults`、`dataPath`、`datasetSize`。

## 图表

1. 每年发行数量（折线/柱状）
2. 每年平均评分
3. 评分分布直方图（后端 50 桶分箱）
4. 评分人数 vs 平均评分（对数横轴，颜色映射发行年代）
5. Top N 高分榜（横向条形）
6. 各年代评分分布箱线图（前端计算 Q1/中位数/Q3、1.5 IQR 离群点与均值）

## 目录结构

```
boardgame-dashboard/
├── pom.xml
├── run.bat
└── src/main/
    ├── java/com/example/dashboard/
    │   ├── DashboardApplication.java
    │   ├── controller/GameController.java
    │   ├── service/GameService.java
    │   └── model/BoardGame.java
    └── resources/
        ├── application.yml
        ├── boardgame_ratings.csv
        ├── static/js/echarts.min.js
        └── templates/index.html
```
