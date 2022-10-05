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

package io.ib67.ezshare.storage.impl;

import io.ib67.ezshare.config.AppConfig;
import io.ib67.ezshare.data.records.FileRecord;
import io.ib67.ezshare.storage.IStorageProvider;
import io.ib67.ezshare.util.RandomHelper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.streams.Pump;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class LocalStorageProvider implements IStorageProvider {
    private final Vertx vertx;
    private final AppConfig config;
    private final Path storageDir;

    @Override
    public void store(RoutingContext ctx, FileUpload file, Consumer<Future<String>> identifierCallback) {
        var id = RandomHelper.randomString();
        var path = storageDir.resolve(id).toAbsolutePath();
        if (storageDir.toFile().getFreeSpace() - file.size() < (long) config.getPreservedSpace() * 1024 * 1024 * 1024) {
            identifierCallback.accept(Future.failedFuture("The disk is full."));
            return;
        }

        identifierCallback.accept(vertx.fileSystem().move(file.uploadedFileName(), path.toAbsolutePath().toString())
                .map(it -> id));
    }

    @Override
    public void download(FileRecord fr, RoutingContext context) {
        context.attachment(fr.fileName());
        var time = System.currentTimeMillis();
        vertx.fileSystem().open(storageDir.resolve(fr.fileIdentifier()).toAbsolutePath().toString(), new OpenOptions().setRead(true))
                .onSuccess(it -> {
                    context.response().headers().set("Content-Type", fr.mimeType());
                    context.response().headers().set("Content-Length", String.valueOf(fr.size()));
                    it.endHandler(a -> {
                        context.end();
                        log.info("[Download] " + fr.fileName() + " tooks " + (System.currentTimeMillis() - time) + "ms");
                    });
                    var pump = Pump.pump(it, context.response());
                    pump.start();
                }).onFailure(it -> {
                    context.end("Failed to download file.");
                });
    }

    @SneakyThrows
    @Override
    public void delete(FileRecord fr) {
        Files.deleteIfExists(storageDir.resolve(fr.fileIdentifier()));
    }
}
