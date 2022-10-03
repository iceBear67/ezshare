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

import io.ib67.ezshare.config.AppConfig;
import io.ib67.ezshare.controller.AppController;
import io.ib67.ezshare.data.DataSource;
import io.ib67.ezshare.data.records.FileRecord;
import io.ib67.ezshare.data.records.URLRecord;
import io.ib67.ezshare.util.RandomHelper;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import lombok.SneakyThrows;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SimpleAppController implements AppController {
    private final AppConfig config;
    private final DataSource dataSource;

    public SimpleAppController(AppConfig config, DataSource dataSource) {
        this.config = config;
        this.dataSource = dataSource;
    }

    @Override
    public void handleRoot(Context ctx) throws Exception {
        if (ctx.userAgent().contains("curl")) {
            ctx.result(Files.readString(Path.of("static/motd.txt")));
            return;
        }
        ctx.header("Content-Type", ContentType.HTML);
        ctx.result(Files.readString(Path.of("static/index.html")));
    }

    @Override
    public void handleShortenUrl(Context context) throws Exception {
        var body = context.body();
        if (body.isEmpty() || body.length() > 128 || !isValid(body)) {
            context.result("Illegal URL. Length in 0~128 and must be valid");
            return;
        }
        String id;
        do {
            id = RandomHelper.randomString();
        } while (dataSource.fetchURLById(id).isPresent());
        var ur = new URLRecord(
                id,
                Instant.now(),
                body
        );
        dataSource.addUrlRecord(ur);
        context.result(config.baseUrl() + "/" + ur.id());
    }

    @Override
    public void handleURLJump(Context ctx) throws Exception {
        var id = ctx.pathParam("id");
        dataSource.fetchURLById(id).ifPresentOrElse(ur -> {
            ctx.header("Location", ur.destination());
            ctx.status(HttpStatus.MOVED_PERMANENTLY);
        }, () -> {
            ctx.status(HttpStatus.TEMPORARY_REDIRECT);
            ctx.header("Location", "/");
        });
    }

    @Override
    public void handleUpload(Context context) throws Exception {
        if (context.uploadedFiles().size() != 1) {
            context.result("You can only upload one file at a time.");
            return;
        }
        var file = context.uploadedFiles().get(0);
        if (config.bannedTypes().contains(file.contentType())) {
            context.result("You cannot upload this kind of file: " + file.contentType());
            return;
        }
        // check size
        if (file.size() / 1024 > config.maxFileSizeKiB()) {
            context.result("File is too big. Max: " + config.maxFileSizeKiB());
            return;
        }
        var available = (config.uploadContainer().toFile().getFreeSpace()) - file.size();
        available = available / 1024 / 1024 / 1024;
        if (available < config.preservedSize()) {
            context.result("File is too big to be held for this server.");
            return;
        }
        // start to reeead
        CompletableFuture<?> future = new CompletableFuture<>();
        var id = RandomHelper.randomString();
        var f = config.uploadContainer().resolve(id).toFile();
        var instant = Instant.now();
        ForkJoinPool.commonPool().submit(() -> streamCopy(id, file.content(), context, future, null, () -> {
            try {
                return Files.newOutputStream(f.toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        },524288));
        future.whenComplete((a, err) -> {
            if (err != null) {
                context.result("Upload Failed.");
                return;
            }
            dataSource.addFileRecord(new FileRecord(
                    id,
                    instant,
                    f.toPath(),
                    (int) file.size(),
                    file.filename(),
                    file.contentType()
            ));
        });
        context.future(() -> future);
    }

    @Override
    public void handleDownload(Context context) {
        var id = context.pathParam("id");
        System.out.println("Client " + context.ip() + " requested to download " + id);
        dataSource.fetchFileById(id).ifPresentOrElse(it -> {
            //Content-Disposition: inline; filename="myfile.txt".
            context.header("Content-Disposition", "inline; filename=\"" + it.fileName().replaceAll("\"", "\\\"") + "\"");
            context.header("Context-Length", String.valueOf(it.size()));
            context.header("Content-Type",it.mimeType());
            var future = new CompletableFuture<>();
            ForkJoinPool.commonPool().submit(() -> {
                try {
                    streamCopy(it.id(), new FileInputStream(it.pathToFile().toFile()), context, future, context.outputStream(), null,524288*4);
                    //context.result(fs);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            context.future(() -> future);
        }, () -> {
            context.status(HttpStatus.NOT_FOUND);
            context.result("Can't find the requested file with ID " + id + ", Is it expired or a typo?");
        });
    }

    @SneakyThrows
    private void streamCopy(String id, InputStream is, Context context, CompletableFuture<?> future, OutputStream fos, Supplier<OutputStream> supplier, int bufferSize) {
        var f = config.uploadContainer().resolve(id).toFile();
        if (fos == null) {
            fos = supplier.get();
        }
        var startTime = System.currentTimeMillis();
        if (is.available() > 0) {
            var buf = is.readNBytes(bufferSize); // 512Kib
            if ((System.currentTimeMillis() - startTime) / 1024 > 16) { // suspended for 16 seconds, lower than 32KiB/sec
                fos.close();
                is.close();
                context.result("Interrupted");
                future.complete(null);
                Files.deleteIfExists(f.toPath());
                return;
            }
            fos.write(buf);
        }
        if (is.available() <= 0) {
            // end.
            context.result(config.baseUrl() + "/files/" + id);
            future.complete(null);
            fos.close();
            is.close();
            return;
        }
        OutputStream finalFos = fos;
        ForkJoinPool.commonPool().submit(() -> streamCopy(id, is, context, future, finalFos, supplier,bufferSize));
    }

    public static boolean isValid(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
