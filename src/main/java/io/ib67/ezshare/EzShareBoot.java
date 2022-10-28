/*
 *
 * MIT License
 *
 * Copyright (c) 2022 iceBear67 and Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.ib67.ezshare;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.ib67.ezshare.config.AppConfig;
import io.ib67.ezshare.controller.EzShareController;
import io.ib67.ezshare.controller.MainController;
import io.ib67.ezshare.data.SimpleDataSource;
import io.ib67.ezshare.storage.IStorageProvider;
import io.ib67.ezshare.storage.impl.LocalStorageProvider;
import io.vertx.core.AsyncResult;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Tuple;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public final class EzShareBoot {
    public static void main(String[] args) {
        new EzShareBoot().init();
    }

    private static final ClassLoader CL = EzShareBoot.class.getClassLoader();
    private static final Path ROOT = Path.of(".");
    private static final Path STATIC = ROOT.resolve("static");

    private final Vertx vertx = Vertx.vertx();
    private final Router router = Router.router(vertx);
    private AppConfig config;
    private Config rawConfig;
    private MainController mainController;
    private Map<String, IStorageProvider> providers = new HashMap<>();
    private ScheduledExecutorService expiryDeleter = Executors.newSingleThreadScheduledExecutor();

    private void init() {
        // try to read buildInfo.
        log.info(readBuildInfo());
        // initialization.
        config = loadConfig();
        loadStorageProviders();
        extractResources();
        // initiate datasource
        loadDatabase(dataSource -> {
            var ds = new SimpleDataSource(dataSource, config);
            mainController = new EzShareController(
                    config,
                    ds,
                    vertx,
                    STATIC,
                    providers
            );
            // load routes
            expiryDeleter.scheduleAtFixedRate(() -> launchExpiry(dataSource, ds), 0L, 1, TimeUnit.MINUTES);
            var bodyHandler = BodyHandler.create()
                    .setHandleFileUploads(true)
                    .setBodyLimit(config.getMaxBodySize() * 1024)
                    .setDeleteUploadedFilesOnEnd(false)
                    .setUploadsDirectory(config.getUploadTmpDir());

            // root handler
            router.get("/").handler(mainController::handleMainPage);
            router.get("/:id").handler(mainController::handleRedirection);
            router.get("/files/:id").handler(mainController::handleDownload);
            var upload = router.post("/");
            if (config.isEnablePassword()) {
                upload.handler(mainController::authPass);
            }
            upload.handler(bodyHandler).handler(mainController::handleUpload);
            // LETS GO
            vertx.createHttpServer(getHttpOptions())
                    .requestHandler(router)
                    .listen(config.getPort(), config.getListenAddr(), this::whenHttpReady);
        });
    }

    private void launchExpiry(JDBCPool dataSource, SimpleDataSource ds) {
        //- ? hour
        dataSource.preparedQuery("SELECT * FROM t_files WHERE creationDate <= CURRENT_TIMESTAMP - ? minute")
                .execute(Tuple.of(config.getExpireHours()))
                .onSuccess(it -> {
                    if (it.size() > 0) log.info("Cleaned {} files", it.size());
                    for (io.vertx.sqlclient.Row row : it) {
                        var i = SimpleDataSource.fromRow(row);
                        ds.removeFileRecord(i)
                                .onSuccess(itz -> {
                                    providers.get(i.storageType()).delete(i);
                                    log.info("File: {} - {}M", i.fileName(), i.size() / 1024 / 1024);
                                }).onFailure(t -> {
                                    log.warn("Failed to remove {}! {} ", i, t);
                                });
                    }
                }).onFailure(t -> {
                    log.warn("Failed to clean files! ", t);
                });
    }

    private void loadStorageProviders() {
        var dest = Path.of(rawConfig.getString("local-destination"));
        if (Files.notExists(dest)) {
            dest.toFile().mkdirs();
        }
        providers.put("local", new LocalStorageProvider(vertx, config, dest));
        //providers.put("local",new LocalStorageProvider(vertx,config.));
    }

    private void extractResources() {
        if (Files.notExists(STATIC)) {
            STATIC.toFile().mkdirs();
        }
        writeIfNotExist(STATIC.resolve("index.html"), processTemplates(readResourceAsText("templates/index.html")));
        writeIfNotExist(STATIC.resolve("motd.txt"), processTemplates(readResourceAsText("templates/motd.txt")));
    }

    private String processTemplates(String readResourceAsText) {
        return readResourceAsText
                .replaceAll("\\{\\{baseUrl}}", config.getBaseUrl())
                .replaceAll("\\{\\{notAllowed}}", config.getBannedMimeTypes().stream().collect(Collectors.joining(", ")));
    }

    @SneakyThrows
    private void writeIfNotExist(Path resolve, String readResourceAsText) {
        if (Files.notExists(resolve)) {
            Files.writeString(resolve, readResourceAsText);
        }
    }

    private String readResourceAsText(String path) {
        try (InputStream inputStream = EzShareBoot.class.getClassLoader().getResourceAsStream(path)) {
            return new String(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException("Cannot read resource " + path, e);
        }
    }

    @SneakyThrows
    private JDBCPool loadDatabase(Consumer<JDBCPool> callback) {
        Class.forName("org.h2.Driver");
        var jo = new JsonObject();
        jo.put("url", config.getJdbcUrl());
        jo.put("driver_class", "org.h2.Driver");
        var pool = JDBCPool.pool(vertx, jo);
        pool.withConnection(it -> {
            System.out.println(it);
            pool.preparedQuery(
                    """
                            CREATE TABLE IF NOT EXISTS t_files (
                              id VARCHAR(6) NOT NULL UNIQUE,
                              creationDate DATETIME NOT NULL,
                              pathToFile VARCHAR(64) NOT NULL,
                              size BIGINT NOT NULL,
                              fileName VARCHAR(16) NOT NULL,
                              mimeType VARCHAR(32) NOT NULL,
                              ip VARCHAR(45) NOT NULL,
                              storageType VARCHAR(16) NOT NULL,
                              PRIMARY KEY (`id`)
                            );
                            """
            ).execute().result();
            pool.preparedQuery("""
                                CREATE TABLE IF NOT EXISTS t_urls (
                                    id VARCHAR(6) NOT NULL UNIQUE,
                                    creationDate DATETIME NOT NULL,
                                    destination VARCHAR(128) NOT NULL,
                                    ip VARCHAR(45) NOT NULL,
                                    PRIMARY KEY (`id`)
                                );
                            """)
                    .execute().result();
            callback.accept(pool);
            return null;
        });
        return pool;
    }


    private void whenHttpReady(AsyncResult<HttpServer> httpServerAsyncResult) {
        if (!httpServerAsyncResult.succeeded()) {
            log.warn("Cannot start HTTP Service, Exiting.");
            System.exit(0);
            return;
        }
        var httpServer = httpServerAsyncResult.result();
        log.info("EzShare is listening on " + config.getListenAddr() + ":" + config.getPort());
    }

    private HttpServerOptions getHttpOptions() {
        var opt = new HttpServerOptions();
        if (config.getKeyPath().isEmpty() != config.getCertPath().isEmpty()) {
            log.warn("One of the key-path and cert-path is missing, We will not enable TLS Support.");
        } else if (!config.getKeyPath().isEmpty()) {
            opt.setPemKeyCertOptions(new PemKeyCertOptions().addCertPath(config.getCertPath()).addKeyPath(config.getKeyPath()));
            opt.setSsl(true);
            log.info("TLS Support is ENABLED");
        }
        return opt;
    }

    private AppConfig loadConfig() {
        var cfg = ROOT.resolve("application.conf");
        if (Files.notExists(cfg)) {
            // extract.
            try (InputStream inputStream = CL.getResourceAsStream("templates/application.conf")) {
                if (inputStream == null) throw new IOException("The default config template is not found");
                Files.write(cfg, inputStream.readAllBytes());
            } catch (IOException e) {
                throw new RuntimeException("Cannot extract config from JAR", e);
            }
        }
        rawConfig = ConfigFactory.parseFile(cfg.toFile());
        return AppConfig.loadConfig(rawConfig);
    }

    private String readBuildInfo() {
        try (var is = CL.getResourceAsStream("buildInfo")) {
            if (is == null) return "Cannot find buildInfo. Is this build corrupted?";
            return new String(is.readAllBytes());
        } catch (IOException e) {
            return "Cannot read buildInfo. Is this build corrupted?";
        }
    }

}
