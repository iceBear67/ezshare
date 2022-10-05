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

package io.ib67.ezshare.controller;

import io.ib67.ezshare.config.AppConfig;
import io.ib67.ezshare.data.DataSource;
import io.ib67.ezshare.data.records.FileRecord;
import io.ib67.ezshare.data.records.URLRecord;
import io.ib67.ezshare.storage.IStorageProvider;
import io.ib67.ezshare.util.RandomHelper;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpStatusClass;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.streams.Pump;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.reactivestreams.ReactiveReadStream;
import io.vertx.ext.reactivestreams.ReactiveWriteStream;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.FileUploadImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class EzShareController implements MainController {
    private final AppConfig config;

    private final DataSource source;
    private final Vertx vertx;
    private final Path staticPath;
    private final Map<String, IStorageProvider> providerMap;

    @Override
    public void handleMainPage(RoutingContext routingContext) {
        var fileUploads = routingContext.fileUploads();
        if (fileUploads.size() != 0) {
            routingContext.end("Please use POST instead of GET");
            return;
        }
        // if curl or browser?
        var ua = routingContext.request().getHeader("User-Agent");
        if (ua == null || ua.contains("curl")) {
            // greeting with motd for curl;
            vertx.fileSystem().readFile(staticPath.resolve("motd.txt").toString())
                    .onSuccess(routingContext::end)
                    .onFailure(fail -> {
                        routingContext.response().setStatusCode(500);
                        log.warn("Cannot load motd.txt: {0}", fail);
                    });
        } else {
            // common browsers
            vertx.fileSystem().readFile(staticPath.resolve("index.html").toString())
                    .onSuccess(routingContext::end)
                    .onFailure(fail -> {
                        routingContext.response().setStatusCode(500);
                        log.warn("Cannot load index.html: {0}", fail);
                    });
        }
    }

    private void handleFileUpload(RoutingContext routingContext, FileUpload fileUpload) {
        if(config.getBannedMimeTypes().contains(fileUpload.contentType())){
            routingContext.end("Banned MIME type.");
            return;
        }
        log.info("Receiving File: " + fileUpload.fileName() + " (" + fileUpload.size() / 1024 / 1024 + "M), " + fileUpload.contentType());
        //routingContext.request().pause();
        var provider = providerMap.get(config.getDefaultStoreType());
        var id = RandomHelper.randomString();
        var time = System.currentTimeMillis();
        provider.store(routingContext, fileUpload, it -> {
            it.onFailure(msg -> {
                routingContext.end(msg.getMessage());
            }).onSuccess(identifier -> {
                log.info("File " + fileUpload.fileName() + " is saved! Took " + (System.currentTimeMillis() - time) / 1000 + "s");
                source.addFileRecord(new FileRecord(
                        id,
                        LocalDateTime.now(),
                        identifier,
                        fileUpload.size(),
                        fileUpload.fileName(),
                        fileUpload.contentType(),
                        routingContext.request().localAddress().hostAddress(),
                        config.getDefaultStoreType()
                ));
                routingContext.end(config.getBaseUrl() + "/files/" + id);
            });
        });
    }

    @Override
    public void handleRedirection(RoutingContext routingContext) {
        var id = routingContext.pathParam("id");
        if (id != null) {
            // query-then-redirect
            source.fetchURLById(id, result -> {
                routingContext.response().setStatusCode(301);
                if (result.succeeded()) {
                    routingContext.redirect(result.result().destination());
                    return;
                }
                routingContext.response().setStatusCode(307);
                routingContext.redirect("/"); // back to main page.
            });
        } else {
            routingContext.response().setStatusCode(307);
            routingContext.redirect("/"); // back to main page.
        }
    }

    @Override
    public void handleDownload(RoutingContext routingContext) {
        var id = routingContext.pathParam("id");
        if (id == null) {
            routingContext.response().setStatusCode(404);
            routingContext.end("ID is missing");
            return;
        }
        source.fetchFileById(id,it->{
            it.onSuccess(fr->{
                var provider = providerMap.get(fr.storageType());
                if(provider == null){
                    log.error("Cannot find storageType {}",fr.storageType());
                    routingContext.end("This file cannot be downloaded, please contact admin.");
                    return;
                }
                provider.download(fr,routingContext);
            }).onFailure(er->{
               // probably not found.
               routingContext.response().setStatusCode(404);
               routingContext.response().end(er.getMessage());
               return;
            });
        });
    }

    @Override
    public void handleUpload(RoutingContext routingContext) {
        if (routingContext.fileUploads().size() != 0) {
            if (routingContext.fileUploads().size() != 1) {
                routingContext.end("You can only upload a file at a time");
                return;
            }
            handleFileUpload(routingContext, routingContext.fileUploads().get(0));
            return;
        }
        // url
        var body = routingContext.body();
        if (!body.available()) {
            routingContext.reroute(HttpMethod.GET, "/");
            return;
        }
        if (body.length() > 128) {
            routingContext.response().setStatusCode(400);
            routingContext.end("URL is too long (> 128)");
            return;
        }
        var url = body.asString();
        if (!validUrl(url)) {
            routingContext.response().setStatusCode(400);
            routingContext.end("URL is not valid.");
            return;
        }
        var id = RandomHelper.randomString();
        //check URL
        source.addUrlRecord(new URLRecord(id, LocalDateTime.now(), url, routingContext.request().localAddress().host()))
                .onFailure(t -> {
                    routingContext.end("Internal Server Error.");
                    log.warn("Can't shorten a url: {}, {}", url, t);
                }).onSuccess(it -> {
                    routingContext.response().setStatusCode(201);
                    routingContext.end(config.getBaseUrl() + "/" + id);
                });
    }

    @Override
    public void authPass(RoutingContext ctx) {
        var data = ctx.request().getHeader("Authorization");
        if (data == null) {
            ctx.response().setStatusCode(403);
            ctx.end("Unauthorized. You should provide a password in Bearer scheme");
            return;
        }
        var sp = data.split(" ");
        if (sp.length != 2 || !"Bearer".equals(sp[0])) {
            ctx.end("Unauthorized. You should provide a password in Bearer scheme");
        }
        var passwd = sp[1];
        if (!config.getPasswords().contains(passwd)) {
            ctx.end("Incorrect Password");
        } else {
            ctx.next();
        }
    }

    private static boolean validUrl(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (MalformedURLException | URISyntaxException e) {
            return false;
        }
    }
}
